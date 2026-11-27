package com.sanepo.offgrid.engine

import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MeshRelayRouter(
    private val localNodeIdProvider: () -> String,
    private val localCallsignProvider: () -> String,
    private val sendIpControl: (envelope: OffGridProtocol.ControlEnvelope, excludeTcpKey: String?) -> Unit,
    private val sendBleControl: (json: String, highPriority: Boolean, excludeBleKey: String?) -> Unit,
    private val sendIpAudio: (packetBytes: ByteArray, excludeTcpKey: String?) -> Unit,
    private val sendBleAudio: (packetBytes: ByteArray, excludeBleKey: String?) -> Unit,
    private val onHelloReceived: (env: OffGridProtocol.ControlEnvelope.Hello, remoteIp: String?, sourcePeerKey: String?) -> Unit,
    private val onChatDeliveredLocally: (env: OffGridProtocol.ControlEnvelope.Chat) -> Unit,
    private val onChatAckReceived: (msgId: String, receiverId: String) -> Unit,
    private val onChatWaitingForSignal: (msgId: String) -> Unit,
    private val onPttFloorReceived: (env: OffGridProtocol.ControlEnvelope.PttFloor) -> Unit,
    private val onSosReceived: (env: OffGridProtocol.ControlEnvelope.SosSignal, remoteIp: String?) -> Unit,
    private val onAudioFramePlayLocal: (frame: OffGridProtocol.AudioFramePacket) -> Unit,
    private val onPingReplyMeasured: (targetNodeId: String, rttMs: Long, hops: Int) -> Unit,
    private val getKnownMsgIds: () -> List<String>,
    private val getMissingMessagesForPeer: (knownIds: Set<String>) -> List<OffGridProtocol.ControlEnvelope.Chat>,
    private val scheduleDelayed: (delayMs: Long, block: () -> Unit) -> Unit,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() }
) {
    private data class PendingOutgoingChat(
        val envelope: OffGridProtocol.ControlEnvelope.Chat,
        var acked: Boolean = false
    )

    private class BoundedKeyCache(private val capacity: Int) {
        private val order = ArrayDeque<String>()
        private val set = HashSet<String>()

        @Synchronized
        fun markSeenIfNew(key: String): Boolean {
            if (set.contains(key)) return false
            set.add(key)
            order.addLast(key)
            while (order.size > capacity) {
                val evicted = order.removeFirst()
                set.remove(evicted)
            }
            return true
        }
    }

    private val controlDedup = BoundedKeyCache(512)
    private val audioDedup = BoundedKeyCache(256)
    private val pendingOutgoing = ConcurrentHashMap<String, PendingOutgoingChat>()
    private val pendingPings = ConcurrentHashMap<String, Long>()
    private val lastHelloRelayAtByNode = ConcurrentHashMap<String, Long>()

    fun sendControlEverywhere(envelope: OffGridProtocol.ControlEnvelope, highPriority: Boolean = true) {
        sendIpControl(envelope, null)
        sendBleControl(OffGridProtocol.encodeControlJson(envelope), highPriority, null)
    }

    fun sendOutgoingChatWithRetry(chat: OffGridProtocol.ControlEnvelope.Chat) {
        controlDedup.markSeenIfNew("C:${chat.msgId}")
        pendingOutgoing[chat.msgId] = PendingOutgoingChat(chat)
        sendControlEverywhere(chat, highPriority = true)

        val retryScheduleMs = longArrayOf(1200L, 3000L, 6500L, 12000L)
        for ((idx, delayMs) in retryScheduleMs.withIndex()) {
            scheduleDelayed(delayMs) {
                val rec = pendingOutgoing[chat.msgId] ?: return@scheduleDelayed
                if (rec.acked) return@scheduleDelayed
                sendControlEverywhere(rec.envelope, highPriority = true)
                if (idx >= 2) {
                    onChatWaitingForSignal(chat.msgId)
                }
            }
        }
    }

    fun retryOutgoingChatManually(msgId: String): Boolean {
        val rec = pendingOutgoing[msgId] ?: return false
        sendControlEverywhere(rec.envelope, highPriority = true)
        return true
    }

    fun sendPing(targetNodeId: String): String {
        val pingId = UUID.randomUUID().toString().take(8)
        val env = OffGridProtocol.ControlEnvelope.Ping(
            pingId = pingId,
            senderId = localNodeIdProvider(),
            targetId = targetNodeId,
            sentAtMs = nowMsProvider(),
            isReply = false,
            ttl = OffGridProtocol.DEFAULT_RELAY_TTL,
            hopCount = 0
        )
        controlDedup.markSeenIfNew("P:$pingId:Q")
        pendingPings[pingId] = env.sentAtMs
        sendControlEverywhere(env, highPriority = true)
        return pingId
    }

    fun onPeerLinkEstablished(sourcePeerKey: String) {
        scheduleDelayed(250L) {
            flushPendingOutbox()
            val req = OffGridProtocol.ControlEnvelope.ChatSyncReq(
                requesterId = localNodeIdProvider(),
                knownMsgIds = getKnownMsgIds()
            )
            sendControlEverywhere(req, highPriority = true)
        }
    }

    fun flushPendingOutbox() {
        for (rec in pendingOutgoing.values) {
            if (!rec.acked) {
                sendControlEverywhere(rec.envelope, highPriority = true)
            }
        }
    }

    fun handleIncomingControlJson(
        rawJson: String,
        remoteIp: String?,
        sourcePeerKey: String?
    ) {
        val env = OffGridProtocol.decodeControlJson(rawJson) ?: return
        val localId = localNodeIdProvider()

        when (env) {
            is OffGridProtocol.ControlEnvelope.Hello -> {
                if (env.nodeId == localId) return
                onHelloReceived(env, remoteIp, sourcePeerKey)

                // Propagate Hello across other connected links (multi-hop radar visibility) at throttled rate
                val now = nowMsProvider()
                val lastRelayed = lastHelloRelayAtByNode[env.nodeId] ?: 0L
                if (env.ttl > 1 && now - lastRelayed >= 5_000L) {
                    lastHelloRelayAtByNode[env.nodeId] = now
                    val relayedHello = env.copy(
                        ttl = env.ttl - 1,
                        hopCount = env.hopCount + 1,
                        viaCallsign = env.viaCallsign ?: localCallsignProvider()
                    )
                    forwardEnvelopeExcludingSource(relayedHello, sourcePeerKey, highPriority = false)
                }
            }
            is OffGridProtocol.ControlEnvelope.Chat -> {
                if (env.senderId == localId) return
                if (!controlDedup.markSeenIfNew("C:${env.msgId}")) return

                onChatDeliveredLocally(env)
                val ack = OffGridProtocol.ControlEnvelope.ChatAck(
                    msgId = env.msgId,
                    receiverId = localId,
                    ttl = OffGridProtocol.DEFAULT_RELAY_TTL,
                    hopCount = 0
                )
                controlDedup.markSeenIfNew("A:${env.msgId}:$localId")
                sendControlEverywhere(ack, highPriority = true)

                if (env.ttl > 1) {
                    val nextHop = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                    forwardEnvelopeExcludingSource(nextHop, sourcePeerKey, highPriority = true)
                }
            }
            is OffGridProtocol.ControlEnvelope.ChatAck -> {
                if (env.receiverId == localId) return
                if (!controlDedup.markSeenIfNew("A:${env.msgId}:${env.receiverId}")) return

                pendingOutgoing[env.msgId]?.acked = true
                onChatAckReceived(env.msgId, env.receiverId)

                if (env.ttl > 1) {
                    val nextHop = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                    forwardEnvelopeExcludingSource(nextHop, sourcePeerKey, highPriority = true)
                }
            }
            is OffGridProtocol.ControlEnvelope.PttFloor -> {
                if (env.senderId == localId) return
                if (!controlDedup.markSeenIfNew("F:${env.senderId}:${env.speaking}:${env.eventTsMs}")) return

                onPttFloorReceived(env)
                if (env.ttl > 1) {
                    val nextHop = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                    forwardEnvelopeExcludingSource(nextHop, sourcePeerKey, highPriority = true)
                }
            }
            is OffGridProtocol.ControlEnvelope.SosSignal -> {
                if (env.senderId == localId) return
                if (!controlDedup.markSeenIfNew("S:${env.senderId}:${env.active}:${env.eventTsMs}")) return

                onSosReceived(env, remoteIp)
                if (env.ttl > 1) {
                    val nextHop = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                    forwardEnvelopeExcludingSource(nextHop, sourcePeerKey, highPriority = true)
                }
            }
            is OffGridProtocol.ControlEnvelope.TcpAudio -> {
                val frame = OffGridProtocol.decodeAudioFrame(env.packetBytes, env.packetBytes.size)
                if (frame != null) {
                    handleIncomingAudioFrame(frame, env.packetBytes, sourcePeerKey)
                }
            }
            is OffGridProtocol.ControlEnvelope.ChatSyncReq -> {
                if (env.requesterId == localId) return
                val missing = getMissingMessagesForPeer(env.knownMsgIds.toSet())
                if (missing.isNotEmpty()) {
                    // Send in small batches of 5 so BLE GATT handles them smoothly
                    var idx = 0
                    while (idx < missing.size) {
                        val end = minOf(missing.size, idx + 5)
                        val batch = OffGridProtocol.ControlEnvelope.ChatSyncBatch(missing.subList(idx, end))
                        sendControlEverywhere(batch, highPriority = false)
                        idx = end
                    }
                }
            }
            is OffGridProtocol.ControlEnvelope.ChatSyncBatch -> {
                for (item in env.items) {
                    if (item.senderId == localId) continue
                    if (controlDedup.markSeenIfNew("C:${item.msgId}")) {
                        onChatDeliveredLocally(item)
                        val ack = OffGridProtocol.ControlEnvelope.ChatAck(
                            msgId = item.msgId,
                            receiverId = localId,
                            ttl = OffGridProtocol.DEFAULT_RELAY_TTL,
                            hopCount = 0
                        )
                        controlDedup.markSeenIfNew("A:${item.msgId}:$localId")
                        sendControlEverywhere(ack, highPriority = true)
                    }
                }
            }
            is OffGridProtocol.ControlEnvelope.Ping -> {
                val kind = if (env.isReply) "R" else "Q"
                if (!controlDedup.markSeenIfNew("P:${env.pingId}:$kind")) return

                if (env.targetId == localId && !env.isReply) {
                    val reply = env.copy(
                        senderId = localId,
                        targetId = env.senderId,
                        isReply = true,
                        ttl = OffGridProtocol.DEFAULT_RELAY_TTL,
                        hopCount = env.hopCount
                    )
                    controlDedup.markSeenIfNew("P:${env.pingId}:R")
                    sendControlEverywhere(reply, highPriority = true)
                } else if (env.targetId == localId && env.isReply) {
                    val startedMs = pendingPings.remove(env.pingId) ?: env.sentAtMs
                    val rtt = (nowMsProvider() - startedMs).coerceAtLeast(1L)
                    onPingReplyMeasured(env.senderId, rtt, env.hopCount)
                } else if (env.ttl > 1) {
                    val nextHop = env.copy(ttl = env.ttl - 1, hopCount = env.hopCount + 1)
                    forwardEnvelopeExcludingSource(nextHop, sourcePeerKey, highPriority = true)
                }
            }
        }
    }

    fun handleIncomingAudioFrame(
        frame: OffGridProtocol.AudioFramePacket,
        rawBytes: ByteArray?,
        sourcePeerKey: String?
    ) {
        if (frame.type == OffGridProtocol.AUDIO_TYPE_PING) return
        if (frame.senderNodeId == localNodeIdProvider()) return

        val rogerFlag = if (frame.type == OffGridProtocol.AUDIO_TYPE_ROGER) 1 else 0
        if (!audioDedup.markSeenIfNew("${frame.senderNodeId}:${frame.sequence}:$rogerFlag")) {
            return
        }

        // Play locally immediately
        onAudioFramePlayLocal(frame)

        // Relay across all OTHER links if TTL remains (Cross-transport Wi-Fi <-> BT bridge & multi-hop extension)
        if (frame.ttl > 1) {
            val nextTtl = frame.ttl - 1
            val excludeTcpKey = sourcePeerKey?.takeIf { it.startsWith("TCP:") }?.removePrefix("TCP:")
            val excludeBleKey = sourcePeerKey?.takeIf { it.startsWith("BLE_") }

            val ipPacket = OffGridProtocol.encodeRelayAudioFrame(
                type = frame.type,
                channelNumber = frame.channelNumber,
                senderNodeId = frame.senderNodeId,
                sequence = frame.sequence,
                ttl = nextTtl,
                pcmBytes = frame.pcmBytes
            )
            sendIpAudio(ipPacket, excludeTcpKey)

            // Transcode 16kHz ADPCM down to compact 8kHz ADPCM when bridging into BLE links
            val blePacket = if (frame.type == OffGridProtocol.AUDIO_TYPE_ADPCM_16K) {
                val pcm16k = ImaAdpcmCodec.decodeToPcm16Le(frame.pcmBytes)
                val pcm8k = ImaAdpcmCodec.downsample16kTo8k(pcm16k)
                val adpcm8 = ImaAdpcmCodec.encodePcm16Le(pcm8k)
                OffGridProtocol.encodeRelayAudioFrame(
                    type = OffGridProtocol.AUDIO_TYPE_ADPCM_8K,
                    channelNumber = frame.channelNumber,
                    senderNodeId = frame.senderNodeId,
                    sequence = frame.sequence,
                    ttl = nextTtl,
                    pcmBytes = adpcm8
                )
            } else {
                ipPacket
            }
            sendBleAudio(blePacket, excludeBleKey)
        }
    }

    private fun forwardEnvelopeExcludingSource(
        envelope: OffGridProtocol.ControlEnvelope,
        sourcePeerKey: String?,
        highPriority: Boolean
    ) {
        val excludeTcp = sourcePeerKey?.takeIf { it.startsWith("TCP:") }?.removePrefix("TCP:")
        val excludeBle = sourcePeerKey?.takeIf { it.startsWith("BLE_") }
        sendIpControl(envelope, excludeTcp)
        sendBleControl(OffGridProtocol.encodeControlJson(envelope), highPriority, excludeBle)
    }
}

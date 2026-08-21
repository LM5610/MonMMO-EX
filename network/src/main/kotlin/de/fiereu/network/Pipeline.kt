package de.fiereu.network

import de.fiereu.network.checksum.NoOpChecksum
import de.fiereu.network.cipher.NoOpSessionCipher
import de.fiereu.network.handlers.ChecksumFrameDecoder
import de.fiereu.network.handlers.ChecksumFrameEncoder
import de.fiereu.network.handlers.CipherDecoder
import de.fiereu.network.handlers.CipherEncoder
import de.fiereu.network.handlers.PacketFrameDecoder
import de.fiereu.network.handlers.PacketFrameEncoder
import de.fiereu.network.handshake.ClientSessionHandshakeHandler
import de.fiereu.network.handshake.ServerSessionHandshakeHandler
import de.fiereu.network.internal.MutableSessionContext
import de.fiereu.network.internal.SESSION_KEY
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelPipeline
import io.netty.handler.logging.LogLevel
import io.netty.handler.logging.LoggingHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import java.util.concurrent.TimeUnit

/**
 * 为特定的网络通道（Channel）安装和配置 Netty 处理管道（Pipeline）。
 *
 * 该方法负责将各种编解码器、日志记录器、超时处理器以及核心的握手处理器
 * 按照特定的顺序添加到 Netty 的 [ChannelPipeline] 中。
 * 管道中的顺序非常重要，它决定了数据包流入（Inbound）和流出（Outbound）时的处理步骤。
 *
 * @param pipeline Netty 的 ChannelPipeline 实例。
 * @param side 指定当前是服务端（Side.SERVER）还是客户端（Side.CLIENT）。
 * @param identity 身份验证信息。服务端需要提供私钥（ServerRoot），客户端需要提供公钥（ClientTrust）。
 * @param applicationProtocol 应用层协议定义，描述了连接成功后使用的数据包格式。
 * @param applicationHandlerFactory 在握手成功后，用于创建应用层实际业务处理器的工厂函数。
 * @param options 管道的配置选项，包含超时时间、最大帧长度以及是否启用日志等。
 */
fun installPipeline(
    pipeline: ChannelPipeline,
    side: Side,
    identity: SessionIdentity,
    applicationProtocol: Protocol,
    applicationHandlerFactory: () -> ProtocolHandler,
    options: PipelineOptions = PipelineOptions(),
) {
  val channel = pipeline.channel()
  // 创建一个可变的会话上下文，并在通道属性中保存它，以便在整个通道生命周期内共享状态
  val session = MutableSessionContext(side, channel, applicationProtocol)
  channel.attr(SESSION_KEY).set(session)

  // 根据当前属于服务端还是客户端，实例化对应的握手处理器 (HandshakeHandler)。
  // 握手阶段是通信的第一步，负责密钥交换和身份验证。
  val handshakeHandler: ProtocolHandler =
      when (side) {
        Side.SERVER -> {
          // 服务端必须提供 ServerRoot 身份以获取服务端私钥
          require(identity is SessionIdentity.ServerRoot) {
            "Server side requires SessionIdentity.ServerRoot"
          }
          ServerSessionHandshakeHandler(
              rootPrivate = identity.rootPrivate,
              applicationProtocol = applicationProtocol,
              applicationHandlerFactory = applicationHandlerFactory,
              options = options,
          )
        }
        Side.CLIENT -> {
          // 客户端必须提供 ClientTrust 身份以获取服务端公钥
          require(identity is SessionIdentity.ClientTrust) {
            "Client side requires SessionIdentity.ClientTrust"
          }
          ClientSessionHandshakeHandler(
              rootPublic = identity.rootPublic,
              applicationProtocol = applicationProtocol,
              applicationHandlerFactory = applicationHandlerFactory,
              options = options,
          )
        }
      }

  // 1. 添加写超时处理器。如果超过指定时间没有数据写出，将触发异常断开连接。
  pipeline.addLast(
      PipelineNames.WRITE_TIMEOUT,
      WriteTimeoutHandler(options.writeTimeout.inWholeSeconds, TimeUnit.SECONDS),
  )

  // 2. 如果开启了底层帧日志（记录原始的加密/未加密二进制流），则添加底层日志处理器。
  if (options.frameLogging) {
    pipeline.addLast(PipelineNames.FRAME_LOGGER, LoggingHandler(LogLevel.TRACE))
  }

  // 3. 添加基于长度的帧解码器和编码器，用于解决 TCP 粘包和拆包问题。
  pipeline.addLast(PipelineNames.FRAME_DECODER, PacketFrameDecoder(options.maxFrameLength))
  pipeline.addLast(PipelineNames.FRAME_ENCODER, PacketFrameEncoder())

  // 4. 添加校验和处理机制（初始阶段通常为 NoOpChecksum 即不进行校验，可能在握手后替换）。
  pipeline.addLast(PipelineNames.CHECKSUM_DECODER, ChecksumFrameDecoder(NoOpChecksum))
  pipeline.addLast(PipelineNames.CHECKSUM_ENCODER, ChecksumFrameEncoder(NoOpChecksum))

  // 5. 添加加密/解密处理器（初始阶段通信未加密，因此使用 NoOpSessionCipher，握手完成后会动态替换为真实的 AES 处理器）。
  pipeline.addLast(PipelineNames.CIPHER_DECODER, CipherDecoder(NoOpSessionCipher))
  pipeline.addLast(PipelineNames.CIPHER_ENCODER, CipherEncoder(NoOpSessionCipher))

  // 6. 如果开启了协议日志，则添加应用层协议日志处理器，专门用于记录解密/解压后的人类可读数据包。
  if (options.frameLogging) {
    pipeline.addLast(PipelineNames.PROTOCOL_LOGGER, LoggingHandler(LogLevel.TRACE))
  }

  // 7. 最后添加核心的业务处理器（初始为握手处理器，握手完成后将被替换为应用层业务处理器）。
  pipeline.addLast(PipelineNames.PROTOCOL_HANDLER, handshakeHandler)
}

/**
 * 内部扩展函数，用于在协议日志记录器（Protocol Logger）之前添加新的处理器（如压缩/解压处理器）。
 *
 * 这样做的目的是为了确保：
 * - 当数据包流入（Inbound）时，数据能在记录日志之前先被解压缩。
 * - 当数据包流出（Outbound）时，记录日志发生在数据被压缩之前。
 * 从而保证日志记录器能够始终记录未压缩的、清晰的数据包信息。
 *
 * @param name 待添加处理器的名称。
 * @param handler 待添加的 ChannelHandler 实例。
 */
internal fun ChannelPipeline.addBeforeProtocolLogger(name: String, handler: ChannelHandler) {
  // 寻找锚点处理器：如果存在日志处理器则以此为锚点，否则以业务处理器为锚点。
  val anchor =
      if (get(PipelineNames.PROTOCOL_LOGGER) != null) {
        PipelineNames.PROTOCOL_LOGGER
      } else {
        PipelineNames.PROTOCOL_HANDLER
      }
  addBefore(anchor, name, handler)
}

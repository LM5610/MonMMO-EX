package de.fiereu.network

import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer

/**
 * 为 [ServerBootstrap] (服务端启动类) 安装网络管道并进行初始化配置。
 *
 * 此扩展函数用于服务端的 Netty 引导程序，在每个新接入的客户端连接通道 (Channel) 建立时， 初始化并安装我们自定义的网络处理管道 (Pipeline)。
 *
 * @param identity 服务端的身份标识，用于在握手和加密阶段证明服务端身份（如包含 RSA 私钥等信息）。
 * @param applicationProtocol 应用层通信所使用的协议定义（包含了数据包的结构、编解码信息等）。
 * @param applicationHandlerFactory 一个工厂函数，用于为每一个新的连接会话创建对应的应用层业务处理处理器 [ProtocolHandler]。
 * @param options 网络管道的额外配置选项，包含心跳机制、压缩阈值等（默认为 [PipelineOptions] 的默认值）。
 * @return 返回配置好 childHandler 后的 [ServerBootstrap] 实例，支持链式调用。
 */
fun ServerBootstrap.installNetwork(
    identity: SessionIdentity.ServerRoot,
    applicationProtocol: Protocol,
    applicationHandlerFactory: () -> ProtocolHandler,
    options: PipelineOptions = PipelineOptions(),
): ServerBootstrap =
    // childHandler 用于配置服务端接收到的每一个子通道 (即客户端的 SocketChannel)
    childHandler(
        object : ChannelInitializer<Channel>() {
          /**
           * 当一个新的 Channel 注册到 EventLoop 时，会回调此方法进行初始化。 在这里我们将实际的业务编解码器和处理器安装到 Channel 的 Pipeline 中。
           */
          override fun initChannel(ch: Channel) {
            installPipeline(
                pipeline = ch.pipeline(),
                side = Side.SERVER, // 指定当前端为服务端
                identity = identity,
                applicationProtocol = applicationProtocol,
                applicationHandlerFactory = applicationHandlerFactory,
                options = options,
            )
          }
        },
    )

/**
 * 为 [Bootstrap] (客户端启动类) 安装网络管道并进行初始化配置。
 *
 * 此扩展函数用于客户端的 Netty 引导程序，在主动连接到服务端建立通道 (Channel) 时， 初始化并安装网络处理管道 (Pipeline)。
 *
 * @param identity 客户端的身份信任标识，通常包含用于验证服务端身份的公钥等信息。
 * @param applicationProtocol 客户端与服务端通信所使用的应用层协议定义。
 * @param applicationHandlerFactory 一个工厂函数，用于为连接会话创建对应的应用层业务处理处理器 [ProtocolHandler]。
 * @param options 网络管道的额外配置选项（默认为 [PipelineOptions] 的默认值）。
 * @return 返回配置好 handler 后的 [Bootstrap] 实例，支持链式调用。
 */
fun Bootstrap.installNetwork(
    identity: SessionIdentity.ClientTrust,
    applicationProtocol: Protocol,
    applicationHandlerFactory: () -> ProtocolHandler,
    options: PipelineOptions = PipelineOptions(),
): Bootstrap =
    // handler 用于配置客户端自身的通道
    handler(
        object : ChannelInitializer<Channel>() {
          /** 当客户端成功创建并注册 Channel 时，会回调此方法进行初始化。 */
          override fun initChannel(ch: Channel) {
            installPipeline(
                pipeline = ch.pipeline(),
                side = Side.CLIENT, // 指定当前端为客户端
                identity = identity,
                applicationProtocol = applicationProtocol,
                applicationHandlerFactory = applicationHandlerFactory,
                options = options,
            )
          }
        },
    )

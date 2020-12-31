/*
 * Copyright 2013-2018 Lilinfeng.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.phei.netty.nio;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Administrator
 * @date 2014年2月16日
 * @version 1.0
 */
public class TimeClientHandle implements Runnable {

	private String host;
	private int port;

	private Selector selector;
	private SocketChannel socketChannel;

	private volatile boolean stop;

	public TimeClientHandle(String host, int port) {
		this.host = host == null ? "127.0.0.1" : host;
		this.port = port;
		try {
			// 创建多路复用器selector
			selector = Selector.open();
			// 打开 SocketChannel 绑定客户端本地地址
			socketChannel = SocketChannel.open();
			// 将 SocketChannel 设置为非阻塞模式
			socketChannel.configureBlocking(false);
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		try {
			doConnect();
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(1);
		}
		
		// 在无线循环体内轮询selector准备就绪的key
		while (!stop) {
			try {
				// selector 休眠时间为1s，无论是否有读写事件，每隔一秒都被唤醒
				selector.select(1000);
				// selector返回就绪状态channel的SelectionKey集合 
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				// 迭代SelectionKey集合
				Iterator<SelectionKey> it = selectedKeys.iterator();
				SelectionKey key = null;
				while (it.hasNext()) {
					key = it.next();
					it.remove();
					try {
						// 进行异步读写操作
						handleInput(key);
					} catch (Exception e) {
						if (key != null) {
							key.cancel();
							if (key.channel() != null)
								key.channel().close();
						}
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
				System.exit(1);
			}
		}

		// 多路复用器关闭后，所有注册在上面的Channel和Pipe等资源都会被自动去注册并关闭，所以不需要重复释放资源
		if (selector != null) {
			try {
				selector.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

	}

	private void handleInput(SelectionKey key) throws IOException {
		if (key.isValid()) {
			// 判断是否连接成功
			SocketChannel sc = (SocketChannel) key.channel();
			// 接收connect事件进行处理
			if (key.isConnectable()) {
				// 判断连接结果，如果连接成功，注册读事件到 selector
				if (sc.finishConnect()) {
					sc.register(selector, SelectionKey.OP_READ);
					doWrite(sc);
				} else
					System.exit(1);// 连接失败，进程退出
			}
			
			if (key.isReadable()) {
				ByteBuffer readBuffer = ByteBuffer.allocate(1024);
				// 异步读取缓冲区数据
				int readBytes = sc.read(readBuffer);
				if (readBytes > 0) {
					// 大于0 ，读到了字节，对字节进行编解码
					// 将缓冲区当前limit设置为 position 设为0，用于对缓冲区的读操作
					readBuffer.flip();
					// 根据缓冲区可读字节大小创建字节数组
					byte[] bytes = new byte[readBuffer.remaining()];
					// 将缓冲区可读字节拷贝到字节数组
					readBuffer.get(bytes);
					String body = new String(bytes, "UTF-8");
					System.out.println("Now is : " + body);
					this.stop = true;
					
				} else if (readBytes < 0) {
					// 对端链路关闭
					key.cancel();
					sc.close();
				} else {
					; // 读到0字节，忽略
				}
			}
		}

	}

	private void doConnect() throws IOException {
		// 异步连接服务端
		if (socketChannel.connect(new InetSocketAddress(host, port))) {
			// 连接成功（获取TCP的ACK应答，建立了物理连接），则注册到多路复用器上读应答
			socketChannel.register(selector, SelectionKey.OP_READ);
			// 发送请求消息
			doWrite(socketChannel);
		} else {
			// 没连接成功, 向多路复用器注册，监听CONNECT事件（监听TCP的ACK应答）
			socketChannel.register(selector, SelectionKey.OP_CONNECT);
		}
	}

	private void doWrite(SocketChannel sc) throws IOException {
		byte[] req = "QUERY TIME ORDER".getBytes();
		ByteBuffer writeBuffer = ByteBuffer.allocate(req.length);
		writeBuffer.put(req);
		writeBuffer.flip();
		sc.write(writeBuffer);
		if (!writeBuffer.hasRemaining()) {
			System.out.println("Send order 2 server succeed.");
		}
	}

}

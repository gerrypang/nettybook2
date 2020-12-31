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
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * @author Administrator
 * @date 2014年2月16日
 * @version 1.0
 */
public class MultiplexerTimeServer implements Runnable {

    private Selector selector;

    private ServerSocketChannel servChannel;

    private volatile boolean stop;

    /**
     * 初始化多路复用器、绑定监听端口
     * 
     * @param port
     */
    public MultiplexerTimeServer(int port) {
		try {
			// 创建多路复用器selector
		    selector = Selector.open();
		    // 打开ServerSocketChannel 用于监听客户端连接，是所有客户端连接的父管道
		    servChannel = ServerSocketChannel.open();
		    // 将ServerSocketChannel 设置为异步非阻塞 
		    servChannel.configureBlocking(false);
		    // 设置backlog 为1024，绑定端口
		    servChannel.socket().bind(new InetSocketAddress(port), 1024);
		    // 将ServerSocketChannel 注册到 selector，监听ACCEPT操作
		    servChannel.register(selector, SelectionKey.OP_ACCEPT);
		    System.out.println("The time server is start in port : " + port);
		} catch (IOException e) {
		    e.printStackTrace();
		    // 如果资源初始化失败则退出
		    System.exit(1);
		}
    }

    public void stop() {
    	this.stop = true;
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Runnable#run()
     */
    @Override
	public void run() {
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
					// 删除处理过的 SelectionKey
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
			} catch (Throwable t) {
				t.printStackTrace();
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
			// 处理新接入的请求消息
			if (key.isAcceptable()) {
				// Accept the new connection
				ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
				// 通过 ServerSocketChannel 的 accept 接收客户端连接，并创建 SocketChannel（完成TCP三次握手，和客户端建立物理连接）
				SocketChannel sc = ssc.accept();
				// SocketChannel 设置为异步非阻塞
				sc.configureBlocking(false);
				// 将客户端连接注册到 selector，监听读操作，可读取客户端发送的消息
				sc.register(selector, SelectionKey.OP_READ);
			}
			
			if (key.isReadable()) {
				// Read the data
				SocketChannel sc = (SocketChannel) key.channel();
				// 创建一个1024k 缓冲区大小的 ByteBuffer 
				ByteBuffer readBuffer = ByteBuffer.allocate(1024);
				// 异步读取缓冲区数据， read 为非阻塞 
				int readBytes = sc.read(readBuffer);
				if (readBytes > 0) {
					// 大于0 ，读到了字节，对字节进行编解码
					// 将缓冲区当前limit设置为position 设为0，用于对缓冲区的读操作
					readBuffer.flip();
					// 根据缓冲区可读字节大小创建字节数组
					byte[] bytes = new byte[readBuffer.remaining()];
					// 将缓冲区可读字节拷贝到字节数组
					readBuffer.get(bytes);
					// 解码
					String body = new String(bytes, "UTF-8");
					System.out.println("The time server receive order : " + body);
					String currentTime = "QUERY TIME ORDER".equalsIgnoreCase(body)
							? new java.util.Date(System.currentTimeMillis()).toString()
							: "BAD ORDER";
					// 发送应答消息给客户端
					doWrite(sc, currentTime);
					
				}  else if (readBytes < 0) {
					// 对端链路关闭
					key.cancel();
					sc.close();
					
				}  else {
					; // 读到0字节，忽略
				}
			}
		}
	}

	private void doWrite(SocketChannel channel, String response) throws IOException {
		if (response != null && response.trim().length() > 0) {
			byte[] bytes = response.getBytes();
			// 根据字节数组创建 ByteBuffer
			ByteBuffer writeBuffer = ByteBuffer.allocate(bytes.length);
			// 将字节数组复制到缓冲区
			writeBuffer.put(bytes);
			writeBuffer.flip();
			// SocketChannel 将缓冲区字节数组发送出去
			channel.write(writeBuffer);
		}
	}
}

package com.minhui.vpn.proxy;

import com.minhui.vpn.*;
import com.minhui.vpn.nat.NatSession;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.tunnel.TcpTunnel;
import com.minhui.vpn.tunnel.TunnelFactory;
import com.minhui.vpn.utils.DebugLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

public class TcpProxyServer implements Runnable {
    private static final String TAG = "TcpProxyServer";
    public boolean Stopped;
    public short port;

    Selector mSelector;
    ServerSocketChannel mServerSocketChannel;
    Thread mServerThread;

    public TcpProxyServer(int port) throws IOException {
        mSelector = Selector.open();

        mServerSocketChannel = ServerSocketChannel.open();
        mServerSocketChannel.configureBlocking(false);
        mServerSocketChannel.socket().bind(new InetSocketAddress(port));
        mServerSocketChannel.register(mSelector, SelectionKey.OP_ACCEPT);
        this.port = (short) mServerSocketChannel.socket().getLocalPort();

        DebugLog.i("AsyncTcpServer listen on %s:%d success.\n",
                mServerSocketChannel.socket().getInetAddress().toString(),
                this.port & 0xFFFF);
    }

    /**
     * 启动TcpProxyServer线程
     */
    public void start() {
        mServerThread = new Thread(this, "TcpProxyServerThread");
        mServerThread.start();
    }

    public void stop() {
        this.Stopped = true;
        try {
            mServerThread.join();
        } catch (InterruptedException ignored) {}
        if (mSelector != null) {
            try {
                mSelector.close();
                mSelector = null;
            } catch (Exception ex) {
                DebugLog.e("TcpProxyServer mSelector.close() catch an exception: %s", ex);
            }
        }

        if (mServerSocketChannel != null) {
            try {
                mServerSocketChannel.close();
                mServerSocketChannel = null;
            } catch (Exception ex) {
                DebugLog.e("TcpProxyServer mServerSocketChannel.close() catch an exception: %s", ex);
            }
        }
    }


    @Override
    public void run() {
        try {
            while (!this.Stopped) {
                int select = mSelector.select();
                if (select == 0) {
                    Thread.sleep(5);
                    continue;
                }
                Set<SelectionKey> selectionKeys = mSelector.selectedKeys();
                if (selectionKeys == null) {
                    continue;
                }
                Iterator<SelectionKey> keyIterator = selectionKeys.iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    if (key.isValid()) {
                        try {
                            if (key.isAcceptable()) {
                                onAccepted(key);
                            } else {
                                //not Tcp?
                                Object attachment = key.attachment();
                                if (attachment instanceof KeyHandler) {
                                    ((KeyHandler) attachment).onKeyReady(key);
                                }
                            }
                        } catch (Exception ex) {
                            DebugLog.e("tcp iterate SelectionKey catch an exception: %s", ex);
                        }
                    }
                    keyIterator.remove();
                }
            }
        } catch (Exception e) {
            DebugLog.e("tcpServer catch an exception: %s", e);
        } finally {
            this.stop();
            DebugLog.i("tcpServer thread exited.");
        }
    }

    private InetSocketAddress getDestAddress(SocketChannel localChannel) {
        short portKey = (short) localChannel.socket().getPort();
        //先session中找，如果找到说明是已经建立的连接
        NatSession session = NatSessionManager.getSession(portKey);
        if (session != null) {
            //如果没有建立连接, 查找转发配置
            forwardConfigInetAddress fci = forwardConfig.getAddress(session.remoteIP, session.remotePort);
            if (fci != null) {
                return new InetSocketAddress(fci.address, fci.port);
            }
            return new InetSocketAddress(localChannel.socket().getInetAddress(), session.remotePort & 0xFFFF);
        }
        return null;
    }

    private void onAccepted(SelectionKey key) {
        TcpTunnel localTunnel = null;
        try {
            SocketChannel localChannel = mServerSocketChannel.accept();
            localTunnel = TunnelFactory.wrap(localChannel, mSelector);
            short portKey = (short) localChannel.socket().getPort();
            InetSocketAddress destAddress = getDestAddress(localChannel);
            if (destAddress != null) {
                TcpTunnel remoteTunnel = TunnelFactory.createTunnelByConfig(destAddress, mSelector, portKey);
                //关联兄弟
                remoteTunnel.setIsHttpsRequest(localTunnel.isHttpsRequest());
                remoteTunnel.setBrotherTunnel(localTunnel);
                localTunnel.setBrotherTunnel(remoteTunnel);
                //开始连接
                remoteTunnel.connect(destAddress);
            }
        } catch (Exception ex) {
            DebugLog.e("TcpProxyServer onAccepted catch an exception: %s", ex);

            if (localTunnel != null) {
                localTunnel.dispose();
            }
        }
    }

}

package com.minhui.vpn.tunnel;

import android.net.VpnService;
import com.minhui.vpn.KeyHandler;
import com.minhui.vpn.nat.NatSessionManager;
import com.minhui.vpn.service.FirewallVpnService;
import com.minhui.vpn.utils.DebugLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class TcpTunnel implements KeyHandler {

    protected InetSocketAddress mDestAddress;
    /**
     * 自己的Channel
     */

    private SocketChannel mInnerChannel;
    /**
     * 发送数据缓存
     */

    private Selector mSelector;
    /**
     * 与外网的通信两个Tunnel负责，
     * 一个负责Apps与TCP代理服务器的通信，一个负责TCP代理服务器与外网服务器的通信
     * Apps与外网服务器的数据交换靠这两个Tunnel来进行
     */
    protected TcpTunnel mBrotherTunnel;
    private boolean mDisposed;
    protected InetSocketAddress mServerEP;
    short portKey;
    ConcurrentLinkedQueue<ByteBuffer> needWriteData = new ConcurrentLinkedQueue<>();

    private final VpnService vpnService;

    public TcpTunnel(VpnService vpnService, SocketChannel innerChannel, Selector selector) {
        this.vpnService = vpnService;
        mInnerChannel = innerChannel;
        mSelector = selector;
    }

    public TcpTunnel(VpnService vpnService, InetSocketAddress serverAddress, Selector selector, short portKey) throws IOException {
        this.vpnService = vpnService;
        SocketChannel innerChannel = SocketChannel.open();
        innerChannel.configureBlocking(false);
        this.mInnerChannel = innerChannel;
        this.mSelector = selector;
        this.mServerEP = serverAddress;
        this.portKey = portKey;
    }

    @Override
    public void onKeyReady(SelectionKey key) {
        if (key.isReadable()) {
            onReadable(key);
        } else if (key.isWritable()) {
            onWritable(key);
        } else if (key.isConnectable()) {
            onConnectable();
        }
    }

    /**
     * 方法调用次序：
     * connect() -> onConnectable() -> onConnected()[子类实现]
     * beginReceived() ->  onReadable() -> afterReceived()[子类实现]
     */

    protected abstract void onConnected() throws Exception;

    protected abstract boolean isTunnelEstablished();

    protected abstract void beforeSend(ByteBuffer buffer) throws Exception;

    protected abstract void afterReceived(ByteBuffer buffer) throws Exception;

    protected abstract void onDispose();

    public void setBrotherTunnel(TcpTunnel brotherTunnel) {
        this.mBrotherTunnel = brotherTunnel;
    }


    public void connect(InetSocketAddress destAddress) throws Exception {
        //保护socket不走VPN
        if (vpnService.protect(mInnerChannel.socket())) {
            mDestAddress = destAddress;
            //注册连接事件
            mInnerChannel.register(mSelector, SelectionKey.OP_CONNECT, this);
            mInnerChannel.connect(mServerEP);
            DebugLog.i("Connecting to %s", mServerEP);
        } else {
            throw new Exception("VPN protect socket failed.");
        }
    }

    public void onConnectable() {
        try {
            if (mInnerChannel.finishConnect()) {
                //通知子类TCP已连接，子类可以根据协议实现握手等
                onConnected();
                DebugLog.i("Connected to %s", mServerEP);
            } else {
                DebugLog.e("Connect to %s failed.", mServerEP);
                this.dispose();
            }
        } catch (Exception e) {
            DebugLog.e("Connect to %s failed: %s", mServerEP, e);
            this.dispose();
        }
    }

    protected void beginReceived() throws Exception {
        if (mInnerChannel.isBlocking()) {
            mInnerChannel.configureBlocking(false);
        }
        mSelector.wakeup();
        //注册读事件
        mInnerChannel.register(mSelector, SelectionKey.OP_READ, this);
    }

    public void onReadable(SelectionKey key) {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(FirewallVpnService.MUTE_SIZE);
            buffer.clear();
            int bytesRead = mInnerChannel.read(buffer);
            if (bytesRead > 0) {
                buffer.flip();

                sendToBrother(key, buffer);
            } else if (bytesRead < 0) {
                //Thread.sleep(300);
                this.dispose();
            }
        } catch (Exception ex) {
            DebugLog.e("onReadable catch an exception: %s", ex);
            this.dispose();
        }
    }

    protected void sendToBrother(SelectionKey key, ByteBuffer buffer) throws Exception {
        //将读到的数据，转发给兄弟
        if (isTunnelEstablished() && buffer.hasRemaining()) {

            mBrotherTunnel.getWriteDataFromBrother(buffer);

        }
    }

    private void getWriteDataFromBrother(ByteBuffer buffer) {
        //如果没有数据尝试直接写
        if (buffer.hasRemaining() && needWriteData.size() == 0) {

            int writeSize = 0;
            try {
                writeSize = write(buffer);
            } catch (Exception e) {
                writeSize = 0;
                e.printStackTrace();
            }
            if (writeSize > 0) {
                return;
            }
        }
        needWriteData.offer(buffer);
        try {
            mSelector.wakeup();
            mInnerChannel.register(mSelector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, this);
        } catch (ClosedChannelException e) {
            e.printStackTrace();
        }
    }

    protected int write(ByteBuffer buffer) throws Exception {
        int byteSendSum = 0;

        beforeSend(buffer);

        while (buffer.hasRemaining()) {
            int byteSent = mInnerChannel.write(buffer);
            byteSendSum += byteSent;
            if (byteSent == 0) {
                break; //不能再发送了，终止循环
            }
        }
        return byteSendSum;
    }


    public void onWritable(SelectionKey key) {
        try {
            ByteBuffer mSendRemainBuffer = needWriteData.poll();
            if (mSendRemainBuffer == null) {
                return;
            }

            write(mSendRemainBuffer);
            if (needWriteData.size() == 0) {
                try {
                    mSelector.wakeup();
                    mInnerChannel.register(mSelector, SelectionKey.OP_READ, this);
                } catch (ClosedChannelException e) {
                    e.printStackTrace();
                }

            }
        } catch (Exception ex) {
            DebugLog.e("onWritable catch an exception: %s", ex);
            this.dispose();
        }
    }

    protected void onTunnelEstablished() throws Exception {
        this.beginReceived(); //开始接收数据
        mBrotherTunnel.beginReceived(); //兄弟也开始接收数据吧
    }

    public void dispose() {
        disposeInternal(true);
    }

    void disposeInternal(boolean disposeBrother) {
        if (!mDisposed) {
            mDisposed = true;
            try {
                mInnerChannel.close();
            } catch (Exception ex) {
                DebugLog.e("InnerChannel close catch an exception: %s", ex);
            }

            if (mBrotherTunnel != null && disposeBrother) {
                //把兄弟的资源也释放了
                mBrotherTunnel.disposeInternal(false);
            }

            mInnerChannel = null;
            mSelector = null;
            mBrotherTunnel = null;

            onDispose();
            NatSessionManager.removeSession(portKey);
        }
    }


}

/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package org.fly.android.localvpn.store;

import android.util.Log;

import org.fly.android.localvpn.Packet;
import org.fly.android.localvpn.firewall.Firewall;
import org.fly.android.localvpn.structs.LRUCache;

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * Transmission Control Block
 */
public class TCB extends Block
{
    private static final String TAG = TCB.class.getSimpleName();

    // TCP has more states, but we need only these
    public enum TCBStatus
    {
        //第一次握手：建立连接时，客户端发送syn包（syn=j）到服务器，客戶端进入SYN_SENT状态，等待服务器确认；SYN：同步序列编号（Synchronize Sequence Numbers）
        SYN_SENT,
        //第二次握手：服务器收到syn包，必须确认客户的SYN（ack=j+1），同时服務器也发送一个SYN包（syn=k），即SYN+ACK包，此时服务器进入SYN_RECV状态；
        SYN_RECEIVED,
        //第三次握手：客户端收到服务器的SYN+ACK包，向服务器发送确认包ACK(ack=k+1），此包发送完毕，客户端和服务器进入ESTABLISHED（TCP连接成功）状态，完成三次握手。
        ESTABLISHED,
        //当某端后发送FIN，對方端回应一个ACK报文给对方，此时则进入到CLOSE_WAIT状态。
        CLOSE_WAIT,
        //已經 FIN - ACK 過的狀態
        LAST_ACK,
    }

    public long mySequenceNum, theirSequenceNum;
    public long myAcknowledgementNum, theirAcknowledgementNum;

    public TCBStatus status;

    public SocketChannel channel;
    public boolean waitingForNetworkData;
    public SelectionKey selectionKey;

    private static LRUCache<String, TCB> tcbCache =
            new LRUCache<>(MAX_CACHE_SIZE, new LRUCache.CleanupCallback<String, TCB>()
            {
                @Override
                public void cleanup(Map.Entry<String, TCB> eldest)
                {
                    eldest.getValue().closeChannel();
                }
            });

    public static TCB getTCB(String ipAndPort)
    {
        synchronized (tcbCache)
        {
            return tcbCache.get(ipAndPort);
        }
    }

    public static void putTCB(String ipAndPort, TCB tcb)
    {
        synchronized (tcbCache)
        {
            tcbCache.put(ipAndPort, tcb);
        }
    }

    public TCB(String ipAndPort,
               Packet.TCPHeader tcpHeader,
               SocketChannel channel,
               Packet referencePacket
               )
    {
        this(ipAndPort,
                new Random().nextInt(Short.MAX_VALUE + 1),
                tcpHeader.sequenceNumber,
                tcpHeader.sequenceNumber + 1,
                tcpHeader.acknowledgementNumber,
                channel,
                referencePacket);
    }

    public TCB(String ipAndPort,
               long mySequenceNum,
               long theirSequenceNum,
               long myAcknowledgementNum,
               long theirAcknowledgementNum,
               SocketChannel channel,
               Packet referencePacket)
    {
        this.ipAndPort = ipAndPort;

        this.mySequenceNum = mySequenceNum;
        this.theirSequenceNum = theirSequenceNum;
        this.myAcknowledgementNum = myAcknowledgementNum;
        this.theirAcknowledgementNum = theirAcknowledgementNum;

        this.channel = channel;
        this.referencePacket = referencePacket;

        firewall = new Firewall(Packet.IP4Header.TransportProtocol.TCP, this);
    }

    public void incrementReplyAck(Packet.TCPHeader tcpHeader, int payloadSize)
    {
        myAcknowledgementNum = tcpHeader.sequenceNumber + payloadSize;
        theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
    }

    public void incrementReplyAck(Packet.TCPHeader tcpHeader)
    {
        incrementReplyAck(tcpHeader, 1);
    }

    public void incrementSeq(int size)
    {
        mySequenceNum += size;

        mySequenceNum = mySequenceNum & 0xffffffffL;

        /*if (mySequenceNum > 0xffffffffL) // 2 ** 32 - 1
            mySequenceNum %= 0x100000000L; // 2 ** 32*/
    }

    public int getRemainSeq()
    {
        return (int)(0xffffffffL - mySequenceNum);
    }

    public void incrementSeq()
    {
        incrementSeq(1);
    }

    public static void closeTCB(TCB tcb)
    {
        Log.d(TAG, "Close Connection:" + tcb.getIpAndPort());

        tcb.closeChannel();
        synchronized (tcbCache)
        {
            tcbCache.remove(tcb.ipAndPort);
        }
    }

    public static void closeAll()
    {
        synchronized (tcbCache)
        {
            Iterator<Map.Entry<String, TCB>> it = tcbCache.entrySet().iterator();
            while (it.hasNext())
            {
                it.next().getValue().closeChannel();
                it.remove();
            }
        }
    }

    protected void closeChannel()
    {
        try
        {
            firewall.clear();
            channel.close();
        }
        catch (IOException e)
        {
            // Ignore
        }
    }
}

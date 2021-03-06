package org.fly.android.localvpn.firewall;

import android.util.Log;

import org.apache.commons.codec.binary.StringUtils;
import org.fly.android.localvpn.contract.IFirewall;
import org.fly.protocol.exception.RequestException;
import org.fly.protocol.exception.ResponseException;
import org.fly.protocol.http.request.Method;
import org.fly.protocol.http.request.Request;
import org.fly.protocol.http.response.Response;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Http implements IFirewall {

    private static final String TAG = Http.class.getSimpleName();

    private static final Pattern inputPattern = Pattern.compile("\\$\\{input\\.([a-z0-9_\\.]*)\\}", Pattern.CASE_INSENSITIVE);

    private Request request = null;

    private Firewall firewall;

    public Http(Firewall firewall) {
        this.firewall = firewall;
    }

    public static boolean maybe(ByteBuffer readableBuffer)
    {
        String str = StandardCharsets.US_ASCII.decode(readableBuffer.duplicate()).toString();
        if (!str.contains(" "))
            return false;

        String[] startLine = str.split("\\s+");

        //因为网址可能会比较长，所以只检查了Method 和 网址的关键字
        return Method.lookup(startLine[0]) != null && (startLine[1].startsWith("/") || startLine[1].contains("://"));
    }

    @Override
    public LinkedList<ByteBuffer> write(ByteBuffer readableBuffer) throws IOException, RequestException, ResponseException {
        if (null == request)
            request = new Request();

        LinkedList<ByteBuffer> results = new LinkedList<>();

        // 依次写入
        request.write(readableBuffer.duplicate());

        String table = null;
        String url = null;
        //等待头完成
        if (request.isHeaderComplete())
        {
            url = request.getUrl();
            table = Firewall.getFilter().matchHttp(url, request.getMethod());

            if (table != null)
                firewall.drop();
            else
                firewall.accept();
        }

        // 包体结束, 清除httpRequest等待通道复用
        if (request.isBodyComplete())
        {
            Log.d(TAG, "HTTP -- " + firewall.getBlock().getIpAndPort() + " " + request.getMethod() + ": " + url);
            Log.d(TAG, request.getHeaderRaw());

            if (table != null)
            {
                table = parse(request, table);

                if (table.startsWith("HTTP/1.1 ") && (table.contains("\r\n\r\n") || table.contains("\n\n")))
                {
                    results.add(StringUtils.getByteBufferUtf8(table));
                } else {
                    Response response = Response.newFixedLengthResponse(table);

                    ByteBuffer buffer = ByteBuffer.allocateDirect(table.length() + Request.BUFF_SIZE);
                    response.send(buffer);

                    results.add(buffer);
                }
            }

            request = null;
        }

        return results;
    }

    private String parse(Request request, String content) {

        if (content.contains("${input."))
        {
            Matcher matcher = inputPattern.matcher(content);

            StringBuffer buffer = new StringBuffer();
            while(matcher.find()){
                String value = request.input(matcher.group(1));
                if (value == null) continue;

                matcher.appendReplacement(buffer, value);
            }

            matcher.appendTail(buffer);

            return buffer.toString();
        }

        return content;
    }
}

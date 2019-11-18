package com.bryanherger.udparser;

import com.google.gson.Gson;
import com.vertica.sdk.*;
import com.vertica.sdk.State.InputState;
import com.vertica.sdk.State.StreamState;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * XmlFilter
 */
public class FIXFilter extends UDFilter {
    private RejectedRecord rejectedRecord;
    private ServerInterface serverInterface;
    private byte lineTerm;
    private static final String SOH = String.valueOf('\001');
    private Map<String, Object> map = new HashMap<>();
    private Set<String> fields = new HashSet<>();

    public FIXFilter(ServerInterface si) {
        serverInterface = si;
        si.log("Instantiated");
        //byte[] ltBytes = "\n".getBytes(Charset.forName("US-ASCII"));
        byte[] ltBytes = SOH.getBytes(Charset.forName("US-ASCII"));
        if (ltBytes.length != 1) {
            throw new IllegalArgumentException("Terminating character is multi-byte");
        }
        lineTerm = ltBytes[0];
    }

    private static int byteArrayIndexOf(byte[] buf, int offset, byte search) {
        for (int i=offset; i<buf.length; i++) {
            if (buf[i] == search) {
                return i;
            }
        }

        return -1;
    }

    /*private ByteBuffer consumeNextLine(DataBuffer input, InputState inputState) {
        int lineEnd = byteArrayIndexOf(input.buf, input.offset, lineTerm);

        if (lineEnd == -1) {
            switch (inputState) {
                case END_OF_FILE:
                case END_OF_CHUNK:
                    ByteBuffer rv = ByteBuffer.wrap(input.buf,
                            input.offset, input.buf.length - input.offset);
                    input.offset = input.buf.length;
                    return rv;
                case OK:
                    return null;
                default:
                    throw new IllegalArgumentException(
                            "Unknown InputState: " + inputState.toString());
            }
        }

        ByteBuffer rv = ByteBuffer.wrap(input.buf,
                input.offset, lineEnd - input.offset);
        input.offset = lineEnd+1; // skip the lineTerm byte
        return rv;
    }*/

    private String consumeNextToken(DataBuffer input, InputState inputState) {
        int lineEnd = byteArrayIndexOf(input.buf, input.offset, lineTerm);
        //serverInterface.log("offset: %d | lineEnd: %d", input.offset, lineEnd);

        if (lineEnd == -1) {
            switch (inputState) {
                case END_OF_FILE:
                case END_OF_CHUNK:
                    ByteBuffer rv = ByteBuffer.wrap(input.buf,
                            input.offset, input.buf.length - input.offset);
                    input.offset = input.buf.length;
                    return new String(rv.array());
                case OK:
                    return null;
                default:
                    throw new IllegalArgumentException(
                            "Unknown InputState: " + inputState.toString());
            }
        }

        ByteBuffer rv = ByteBuffer.wrap(input.buf,
                input.offset, lineEnd - input.offset);
        byte[] bb = new byte[lineEnd - input.offset];
        rv.get(bb, 0, lineEnd - input.offset);
        //serverInterface.log("wrap offset: %d | lineEnd - offset: %d | capacity: %d", input.offset, lineEnd - input.offset, rv.capacity());
        input.offset = lineEnd+1; // skip the lineTerm byte
        String fixToken = new String(rv.slice().array());
        //serverInterface.log("rv = %s, bb = %s", fixToken, new String(bb));
        return new String(bb);
    }

    @Override
    public StreamState process(ServerInterface srvInterface, DataBuffer input,
                               InputState inputState, DataBuffer output) throws UdfException {
        Gson gson=new Gson();
        String outputString = "[";
        boolean addComma = false;
        while (input.offset < input.buf.length) {
            String lineBytes = consumeNextToken(input, inputState);

            if (lineBytes == null) {
                return StreamState.INPUT_NEEDED;
            }

            //String fixToken = new String(lineBytes.array());
            serverInterface.log("Got token: %s", lineBytes);
            if (lineBytes.startsWith("8=FIX") && map.size() > 0) {
                if (addComma) {
                    outputString = outputString + ",";
                } else {
                    addComma = true;
                }
                outputString = outputString + gson.toJson(map);
                map.clear();
            }
            String[] tokens = lineBytes.split("=");
            map.put(tokens[0], tokens[1]);
        }
        outputString = outputString + "]";

        try {
            output.buf = outputString.getBytes("UTF-8");
            output.offset = outputString.getBytes("UTF-8").length;
        } catch (UnsupportedEncodingException uex) {
            serverInterface.log("FIX2JSON: uex: %s", uex.getMessage());
        }

        return StreamState.DONE;
    }

}

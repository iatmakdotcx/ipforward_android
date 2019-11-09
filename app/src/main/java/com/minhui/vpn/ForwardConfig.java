package com.minhui.vpn;

import com.minhui.vpn.utils.CommonMethods;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;

public class ForwardConfig {
    private static ForwardConfig instance;
    private final ConcurrentHashMap<Long, forwardConfigInetAddress> forwardTable = new ConcurrentHashMap<>();

    private ForwardConfig(){ }

    public static ForwardConfig getInstance() {
        if(instance == null){
            instance = new ForwardConfig();
        }
        return instance;
    }

    public forwardConfigInetAddress getAddress(Long portKey) {
        return forwardTable.get(portKey);
    }
    public forwardConfigInetAddress getAddress(int ip, short port) {
        long key = ((long)ip << 32) | port;
        return getAddress(key);
    }

    public void init(String cfgData)
    {
        forwardTable.clear();
        for (String aLine : cfgData.split("\\n")) {
            if (aLine.startsWith("//")) continue;
            String[] aForwardData = aLine.replace(" ","").trim().split("=");
            if (aForwardData.length == 2) {
                try {
                    String[] fromData = aForwardData[0].split(":");
                    if (fromData.length != 2) continue;
                    int fromIp = CommonMethods.ipStringToInt(fromData[0]);
                    short fromPort = Short.parseShort(fromData[1]);

                    String[] toData = aForwardData[1].split(":");
                    if (toData.length != 2) continue;
                    int toIp = CommonMethods.ipStringToInt(toData[0]);
                    short toPort = Short.parseShort(toData[1]);

                    long key = ((long)fromIp << 32) | fromPort;
                    forwardTable.put(key, new forwardConfigInetAddress(toIp, toPort, true));
                } catch (Exception ex) {
                }
            }
        }
    }

}

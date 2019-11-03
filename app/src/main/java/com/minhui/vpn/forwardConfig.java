package com.minhui.vpn;

import com.minhui.vpn.utils.CommonMethods;

import java.util.concurrent.ConcurrentHashMap;

public class forwardConfig {
    private static final ConcurrentHashMap<Long, forwardConfigInetAddress> sessions = new ConcurrentHashMap<>();

    public static forwardConfigInetAddress getAddress(Long portKey) {
        return sessions.get(portKey);
    }
    public static forwardConfigInetAddress getAddress(int ip, short port) {
        long key = ((long)ip << 32) | port;
        return getAddress(key);
    }

    public static void init(String cfgData)
    {
        sessions.clear();
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
                    sessions.put(key, new forwardConfigInetAddress(toIp, toPort));
                } catch (Exception ex) {
                }
            }
        }

    }


}

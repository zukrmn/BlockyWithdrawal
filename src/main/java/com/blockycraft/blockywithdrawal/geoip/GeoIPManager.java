package com.blockycraft.blockywithdrawal.geoip;

import com.blockycraft.blockygeoip.BlockyGeoIP;
import com.blockycraft.blockygeoip.BlockyGeoIPAPI;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class GeoIPManager {
    private boolean geoIPEnabled = false;
    private BlockyGeoIPAPI api;

    public GeoIPManager() {
        if (Bukkit.getPluginManager().isPluginEnabled("BlockyGeoIP")) {
            try {
                api = BlockyGeoIP.getInstance().getApi();
                geoIPEnabled = true;
            } catch (Exception e) {
                geoIPEnabled = false;
                Bukkit.getServer().getLogger().severe("Error while getting BlockyGeoIP API instance: " + e.getMessage());
            }
        }
    }

    public String getPlayerLanguage(Player player) {
        if (geoIPEnabled && api != null) {
            try {
                String lang = api.getPlayerLanguage(player.getUniqueId());
                return lang != null ? lang.toLowerCase() : "en";
            } catch (Exception e) {
                // API might not be available or something went wrong
                return "en";
            }
        }
        return "en";
    }
}

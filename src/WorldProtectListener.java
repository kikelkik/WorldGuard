// $Id$
/*
 * WorldProtect
 * Copyright (C) 2010 sk89q <http://www.sk89q.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.logging.Handler;
import java.util.logging.ConsoleHandler;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.*;
import com.sk89q.worldprotect.*;
import java.util.logging.FileHandler;

/**
 * Event listener for Hey0's server mod.
 *
 * @author sk89q
 */
public class WorldProtectListener extends PluginListener {
    /**
     * Logger.
     */
    private static final Logger logger = Logger.getLogger("Minecraft.WorldProtect");
    /**
     * Properties file for CraftBook.
     */
    private PropertiesFile properties = new PropertiesFile("worldprotect.properties");

    private boolean enforceOneSession;
    private boolean blockCreepers;
    private boolean blockTNT;
    private boolean blockLighter;
    private boolean preventLavaFire;
    private boolean disableAllFire;
    private boolean simulateSponge;
    private Set<Integer> fireNoSpreadBlocks;
    private Set<Integer> allowedLavaSpreadOver;
    private boolean classicWater;
    private Map<Integer,BlacklistEntry> blacklist;

    /**
     * Convert a comma-delimited list to a set of integers.
     * 
     * @param str
     * @return
     */
    private static Set<Integer> toBlockIDSet(String str) {
        if (str.trim().length() == 0) {
            return null;
        }
        
        String[] items = str.split(",");
        Set<Integer> result = new HashSet<Integer>();

        for (String item : items) {
            try {
                result.add(Integer.parseInt(item.trim()));
            } catch (NumberFormatException e) {
                int id = etc.getDataSource().getItem(item.trim());
                if (id != 0) {
                    result.add(id);
                } else {
                    logger.log(Level.WARNING, "WorldProtect: Unknown block name: "
                            + item);
                }
            }
        }

        return result;
    }

    /**
     * Load the configuration
     */
    public void loadConfiguration() {
        properties.load();

        enforceOneSession = properties.getBoolean("enforce-single-session", true);
        blockCreepers = properties.getBoolean("block-creepers", false);
        blockTNT = properties.getBoolean("block-tnt", false);
        blockLighter = properties.getBoolean("block-lighter", false);
        preventLavaFire = properties.getBoolean("disable-lava-fire", false);
        disableAllFire = properties.getBoolean("disable-all-fire-spread", false);
        fireNoSpreadBlocks = toBlockIDSet(properties.getString("disallowed-fire-spread-blocks", ""));
        allowedLavaSpreadOver = toBlockIDSet(properties.getString("allowed-lava-spread-blocks", ""));
        classicWater = properties.getBoolean("classic-water", false);
        simulateSponge = properties.getBoolean("simulate-sponge", false);

        try {
            blacklist = loadBlacklist(new File("worldprotect-blacklist.txt"));
        } catch (FileNotFoundException e) {
            logger.log(Level.WARNING, "WorldProtect blacklist does not exist.");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not load WorldProtect blacklist: "
                    + e.getMessage());
        }

        Logger blacklistLogger = Logger.getLogger("WorldProtect.Blacklist");
        blacklistLogger.setUseParentHandlers(false);
        for (Handler handler : blacklistLogger.getHandlers()) {
            blacklistLogger.removeHandler(handler);
        }

        // Blacklist log to console
        if (properties.getBoolean("blacklist-log-console", true)) {
            Handler handler = new ConsoleHandler();
            handler.setFormatter(new ConsoleLogFormat());
            blacklistLogger.addHandler(handler);
        }

        // Blacklist log file
        String logFile = properties.getString("blacklist-log-file", "").trim();
        int limit = properties.getInt("blacklist-log-file-limit", 1024 * 1024 * 5);
        int count = properties.getInt("blacklist-log-file-count", 10);
        if (logFile.length() > 0) {
            try {
                Handler handler = new FileHandler(logFile, limit, count, true);
                handler.setFormatter(new SimpleLogFormat());
                blacklistLogger.addHandler(handler);
            } catch (IOException e) {
                logger.log(Level.WARNING, "Could not open blacklist log file: "
                        + e.getMessage());
            }
        }
    }
    
    /**
     * Called during the early login process to check whether or not to kick the
     * player
     *
     * @param user
     * @return kick reason. null if you don't want to kick the player.
     */
    public String onLoginChecks(String user) {
        if (enforceOneSession) {
            for (Player player : etc.getServer().getPlayerList()) {
                if (player.getName().equalsIgnoreCase(user)) {
                    player.kick("Logged in from another location.");
                }
            }
        }

        return null;
    }

    /**
     * Called when someone presses right click. If they right clicked with a
     * block you can return true to cancel that. You can intercept this to add
     * your own right click actions to different item types (see itemInHand)
     *
     * @param player
     * @param blockPlaced
     * @param blockClicked
     * @param itemInHand
     * @return false if you want the action to go through
     */
    public boolean onBlockCreate(Player player, Block blockPlaced, Block blockClicked,
            int itemInHand) {
        if (blacklist != null) {
            BlacklistEntry entry = blacklist.get(itemInHand);
            if (entry != null) {
                if (!entry.onRightClick(itemInHand, player)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /**
     * Called when a person left clicks a block.
     *
     * @param player
     * @param block
     * @return
     */
    public boolean onBlockDestroy(Player player, Block block) {
        if (blacklist != null) {
            BlacklistEntry entry = blacklist.get(player.getItemInHand());
            if (entry != null) {
                if (entry.onLeftClick(player.getItemInHand(), player)) {
                    return true;
                }
            }
            
            entry = blacklist.get(block.getType());
            if (entry != null) {
                if (!entry.onDestroy(block, player)) {
                    return true;
                }
            }
        }
        
        return false;
    }

    /*
     * Called when either a lava block or a lighter tryes to light something on fire.
     * block status depends on the light source:
     * 1 = lava.
     * 2 = lighter (flint + steel).
     * 3 = spread (dynamic spreading of fire).
     * @param block
     *          block that the fire wants to spawn in.
     *
     * @return true if you dont want the fire to ignite.
     */
    public boolean onIgnite(Block block, Player player) {
        if (preventLavaFire && block.getStatus() == 1) {
            return true;
        }
        
        if (blockLighter && block.getStatus() == 2) {
            return !player.canUseCommand("/uselighter")
                    && !player.canUseCommand("/lighter");
        }

        if (disableAllFire && block.getStatus() == 3) {
            return true;
        }

        if (fireNoSpreadBlocks != null && block.getStatus() == 3) {
            int x = block.getX();
            int y = block.getY();
            int z = block.getZ();
            if (fireNoSpreadBlocks.contains(etc.getServer().getBlockIdAt(x, y - 1, z))
                    || fireNoSpreadBlocks.contains(etc.getServer().getBlockIdAt(x + 1, y, z))
                    || fireNoSpreadBlocks.contains(etc.getServer().getBlockIdAt(x - 1, y, z))
                    || fireNoSpreadBlocks.contains(etc.getServer().getBlockIdAt(x, y, z - 1))
                    || fireNoSpreadBlocks.contains(etc.getServer().getBlockIdAt(x, y, z + 1))) {
                return true;
            }
        }

        return false;
    }

    /*
     * Called when a dynamite block or a creeper is triggerd.
     * block status depends on explosive compound:
     * 1 = dynamite.
     * 2 = creeper.
     * @param block
     *          dynamite block/creeper location block.
     *
     * @return true if you dont the block to explode.
     */
    public boolean onExplode(Block block) {
        if (blockCreepers && block.getStatus() == 2) {
            return true;
        }

        if (blockTNT && block.getStatus() == 1) {
            return true;
        }

        return false;
    }

    /*
     * Called when lava wants to flow to a certain block.
     * block status represents the type that wants to flow.
     * (10 & 11 for lava and 8 & 9 for water)
     *
     * @param block
     *          the block beneath where the substance wants to flow to.
     *
     * for example:
     * lava want to flow to block x,y,z then the param block is the block x,y-1,z.
     *
     * @return true if you dont want the substance to flow.
     */
    public boolean onFlow(Block block) {
        if (simulateSponge && (block.getStatus() == 8 || block.getStatus() == 9)) {
            int ox = block.getX();
            int oy = block.getY() + 1;
            int oz = block.getZ();

            Server server = etc.getServer();

            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -4; z <= 4; z++) {
                        if (server.getBlockIdAt(ox + x, oy + y, oz + z) == 19) {
                            return true;
                        }
                    }
                }
            }
        }

        if (classicWater && (block.getStatus() == 8 || block.getStatus() == 9)) {
            int blockBelow = etc.getServer().getBlockIdAt(block.getX(), block.getY(), block.getZ());
            if (blockBelow != 0 && blockBelow != 8 && blockBelow != 9) {
                etc.getServer().setBlockAt(9, block.getX(), block.getY() + 1, block.getZ());
                return false;
            }
        }

        if (allowedLavaSpreadOver != null && (block.getStatus() == 10 || block.getStatus() == 11)) {
            if (!allowedLavaSpreadOver.contains(block.getType())) {
                return true;
            }
        }

        return false;
    }

    /**
     * Called on player disconnect
     *
     * @param player
     */
    public void onDisconnect(Player player) {
        BlacklistEntry.forgetPlayer(player);
    }

    /**
     * Load the blacklist.
     * 
     * @param file
     * @return
     * @throws IOException
     */
    public Map<Integer,BlacklistEntry> loadBlacklist(File file)
            throws IOException {
        FileReader input = null;
        Map<Integer,BlacklistEntry> blacklist = new HashMap<Integer,BlacklistEntry>();

        try {
            input = new FileReader(file);
            BufferedReader buff = new BufferedReader(input);

            String line;
            List<BlacklistEntry> entries = null;
            while ((line = buff.readLine()) != null) {
                line = line.trim();

                // Blank line
                if (line.length() == 0) {
                    continue;
                } else if (line.charAt(0) == ';' || line.charAt(0) == '#') {
                    continue;
                }

                if (line.matches("^\\[.*\\]$")) {
                    String[] items = line.substring(1, line.length() - 1).split(",");
                    entries = new ArrayList<BlacklistEntry>();

                    for (String item : items) {
                        int id = 0;

                        try {
                            id = Integer.parseInt(item.trim());
                        } catch (NumberFormatException e) {
                            id = etc.getDataSource().getItem(item.trim());
                            if (id == 0) {
                                logger.log(Level.WARNING, "WorldProtect: Unknown block name: "
                                        + item);
                                break;
                            }
                        }

                        BlacklistEntry entry = new BlacklistEntry();
                        blacklist.put(id, entry);
                        entries.add(entry);
                    }
                } else if (entries != null) {
                    String[] parts = line.split("=");

                    if (parts.length == 1) {
                        logger.log(Level.WARNING, "Found option with no value "
                                + file.getName() + " for '" + line + "'");
                        continue;
                    }

                    boolean unknownOption = false;

                    for (BlacklistEntry entry : entries) {
                        if (parts[0].equalsIgnoreCase("ignore-groups")) {
                            entry.setIgnoreGroups(parts[1].split(","));
                        } else if(parts[0].equalsIgnoreCase("on-destroy")) {
                            entry.setDestroyActions(parts[1].split(","));
                        } else if(parts[0].equalsIgnoreCase("on-left")) {
                            entry.setLeftClickActions(parts[1].split(","));
                        } else if(parts[0].equalsIgnoreCase("on-right")) {
                            entry.setRightClickActions(parts[1].split(","));
                        } else {
                            unknownOption = true;
                        }
                    }

                    if (unknownOption) {
                        logger.log(Level.WARNING, "Unknown option '" + parts[0]
                                + "' in " + file.getName() + " for '" + line + "'");
                    }
                } else {
                    logger.log(Level.WARNING, "Found option with no heading "
                            + file.getName() + " for '" + line + "'");
                }
            }

            return blacklist.isEmpty() ? null : blacklist;
        } finally {
            try {
                if (input != null) {
                    input.close();
                }
            } catch (IOException e2) {
            }
        }
    }
}
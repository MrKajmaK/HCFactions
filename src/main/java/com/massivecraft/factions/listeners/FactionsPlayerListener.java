package com.massivecraft.factions.listeners;

import com.massivecraft.factions.*;
import com.massivecraft.factions.event.FPlayerJoinEvent;
import com.massivecraft.factions.event.FPlayerLeaveEvent;
import com.massivecraft.factions.scoreboards.FScoreboard;
import com.massivecraft.factions.scoreboards.FTeamWrapper;
import com.massivecraft.factions.scoreboards.sidebar.FDefaultSidebar;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Relation;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.VisualizeUtil;
import com.massivecraft.factions.zcore.StuckRequest;
import com.massivecraft.factions.zcore.persist.MemoryFPlayer;
import com.massivecraft.factions.zcore.util.TL;
import com.massivecraft.factions.zcore.util.TextUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;


public class FactionsPlayerListener implements Listener {

    public P p;

    public FactionsPlayerListener(P p) {
        this.p = p;
        for (Player player : p.getServer().getOnlinePlayers()) {
            initPlayer(player);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerJoin(PlayerJoinEvent event) {
        initPlayer(event.getPlayer());
    }

    private void initPlayer(Player player) {
        final MemoryFPlayer fme = (MemoryFPlayer) FPlayers.getInstance().login(player);

        fme.setName(player.getName());
        fme.setOnline(true);

        // Update the lastLoginTime for this fplayer
        fme.setLastLoginTime(System.currentTimeMillis());

        // prevent damage to other players directly after login
        if (Conf.noPVPDamageToOthersForXSecondsAfterLogin > 0) {
            fme.setLoginPvpDisabled(true);
        }

        // Set players location and notify them where they are
        fme.setLastStoodAt(
                player.getLocation().getWorld().getName(),
                FLocation.blockToChunk(player.getLocation().getBlockX()),
                FLocation.blockToChunk(player.getLocation().getBlockZ())
        );

        fme.saveStats(player); // set kills / deaths

        FScoreboard.init(fme);

        // Check for Faction announcements. Let's delay this so they actually see it.
        Bukkit.getScheduler().runTaskLaterAsynchronously(P.p, () -> {
            if (fme.isOnline()) {
                fme.getFaction().sendUnreadAnnouncements(fme);
            }
        }, 33L);

        if (P.p.getConfig().getBoolean("scoreboard.default-enabled", false)) {
            int interval = P.p.getConfig().getInt("default-update-interval", 5);
            P.p.debug("Update interval: " + interval + " ticks: " + interval * 20);
            FScoreboard.get(fme).setDefaultSidebar(new FDefaultSidebar(), interval * 20);
        }

        FScoreboard.get(fme).setSidebarVisibility(P.p.cmdBase.cmdSB.showBoard(fme));

        new BukkitRunnable() {
            @Override
            public void run() {
                Faction myFaction = fme.getFaction();

                final Set<FPlayer> onlineMembers = myFaction.getFPlayersWhereOnline(true);

                if (!myFaction.isWilderness()) {
                    onlineMembers.stream().filter(other -> other != fme && other.isMonitoringJoins()).forEach(other -> other.msg(TL.FACTION_LOGIN, fme.getName()));
                }
            }
        }.runTaskAsynchronously(P.p);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        FPlayer me = FPlayers.getInstance().logout(event.getPlayer());

        // and update their last login time to point to when the logged off, for auto-remove routine
        me.setLastLoginTime(System.currentTimeMillis());

        ((MemoryFPlayer) me).saveStats(event.getPlayer()); // cache kills / deaths
        ((MemoryFPlayer) me).setOnline(false);

        // if player is waiting for a fstuck request but leaves, cancel and alert when they come back
        if (P.p.getStuckRequestMap().containsKey(me.getPlayer().getUniqueId())) {
            me.getFaction().addAnnouncement(me, TL.COMMAND_STUCK_CANCELLED.toString());
            P.p.getStuckRequestMap().get(me.getPlayer().getUniqueId()).cancel();
        }

        Faction myFaction = me.getFaction();
        if (!myFaction.isWilderness()) {
            myFaction.memberLoggedOff();
        }

        if (!myFaction.isWilderness()) {
            for (FPlayer player : myFaction.getFPlayersWhereOnline(true)) {
                if (player != me && player.isMonitoringJoins()) {
                    player.msg(TL.FACTION_LOGOUT, me.getName());
                }
            }
        }

        FScoreboard.remove(me);
    }

    // Holds the next time a player can have a map shown.
    private HashMap<UUID, Long> showTimes = new HashMap<UUID, Long>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // clear visualization
        if (event.getFrom().getBlockX() != event.getTo().getBlockX() || event.getFrom().getBlockY() != event.getTo().getBlockY() || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            VisualizeUtil.clear(event.getPlayer());

            // cancel stuck request if one exists and player is outside radius
            if (P.p.getStuckRequestMap().containsKey(event.getPlayer().getUniqueId())) {
                StuckRequest request = P.p.getStuckRequestMap().get(event.getPlayer().getUniqueId());
                if (request.isOutsideRadius(event.getTo())) {
                    request.alert();
                    request.cancel();
                }
            }
        }

        // quick check to make sure player is moving between chunks; good performance boost
        if (event.getFrom().getBlockX() >> 4 == event.getTo().getBlockX() >> 4 && event.getFrom().getBlockZ() >> 4 == event.getTo().getBlockZ() >> 4 && event.getFrom().getWorld() == event.getTo().getWorld()) {
            return;
        }

        Player player = event.getPlayer();
        FPlayer me = FPlayers.getInstance().getByPlayer(player);

        // Did we change coord?
        FLocation from = me.getLastStoodAt();
        FLocation to = new FLocation(event.getTo());

        if (from.equals(to)) {
            return;
        }

        // Yes we did change coord (:

        me.setLastStoodAt(to);

        // Did we change "host"(faction)?
        Faction factionFrom = Board.getInstance().getFactionAt(from);
        Faction factionTo = Board.getInstance().getFactionAt(to);
        boolean changedFaction = (factionFrom != factionTo);

        if (me.isMapAutoUpdating()) {
            if (showTimes.containsKey(player.getUniqueId()) && (showTimes.get(player.getUniqueId()) > System.currentTimeMillis())) {
                if (P.p.getConfig().getBoolean("findfactionsexploit.log", false)) {
                    P.p.log(Level.WARNING, "%s tried to show a faction map too soon and triggered exploit blocker.", player.getName());
                }
            } else {
                me.sendMessage(Board.getInstance().getMap(me.getFaction(), to, player.getLocation().getYaw()));
                showTimes.put(player.getUniqueId(), System.currentTimeMillis() + P.p.getConfig().getLong("findfactionsexploit.cooldown", 2000));
            }
        } else {
            Faction myFaction = me.getFaction();
            String ownersTo = myFaction.getOwnerListString(to);

            if (changedFaction) {
                me.sendFactionHereMessage(factionFrom);
                if (Conf.ownedAreasEnabled && Conf.ownedMessageOnBorder && myFaction == factionTo && !ownersTo.isEmpty()) {
                    me.sendMessage(TL.GENERIC_OWNERS.format(ownersTo));
                }
            } else if (Conf.ownedAreasEnabled && Conf.ownedMessageInsideTerritory && myFaction == factionTo && !myFaction.isWilderness()) {
                String ownersFrom = myFaction.getOwnerListString(from);
                if (Conf.ownedMessageByChunk || !ownersFrom.equals(ownersTo)) {
                    if (!ownersTo.isEmpty()) {
                        me.sendMessage(TL.GENERIC_OWNERS.format(ownersTo));
                    } else if (!TL.GENERIC_PUBLICLAND.toString().isEmpty()) {
                        me.sendMessage(TL.GENERIC_PUBLICLAND.toString());
                    }
                }
            }
        }

        if (me.getAutoClaimFor() != null) {
            me.attemptClaim(me.getAutoClaimFor(), event.getTo(), true);
        } else if (me.isAutoSafeClaimEnabled()) {
            if (!Permission.MANAGE_SAFE_ZONE.has(player)) {
                me.setIsAutoSafeClaimEnabled(false);
            } else {
                if (!Board.getInstance().getFactionAt(to).isSafeZone()) {
                    Board.getInstance().setFactionAt(Factions.getInstance().getSafeZone(), to);
                    me.msg(TL.PLAYER_SAFEAUTO);
                }
            }
        } else if (me.isAutoWarClaimEnabled()) {
            if (!Permission.MANAGE_WAR_ZONE.has(player)) {
                me.setIsAutoWarClaimEnabled(false);
            } else {
                if (!Board.getInstance().getFactionAt(to).isWarZone()) {
                    Board.getInstance().setFactionAt(Factions.getInstance().getWarZone(), to);
                    me.msg(TL.PLAYER_WARAUTO);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        // only need to check right-clicks and physical as of MC 1.4+; good performance boost
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.PHYSICAL) {
            return;
        }

        Block block = event.getClickedBlock();
        Player player = event.getPlayer();

        if (block == null) {
            return;  // clicked in air, apparently
        }

        // handle boat glitching
        if (Conf.handleExploitBoatClipping && player.getItemInHand().getType() == Material.BOAT) {
            if (!playerCanUseItemHere(player, block.getLocation(), Material.BOAT, false)) {
                event.setCancelled(true);
                return;
            }
        }

        if (!canPlayerUseBlock(player, block, false)) {
            event.setCancelled(true);
            return;
        }

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;  // only interested on right-clicks for below
        }

        if (!playerCanUseItemHere(player, block.getLocation(), event.getMaterial(), false)) {
            event.setCancelled(true);
        }
    }


    public static boolean playerCanUseItemHere(Player player, Location location, Material material, boolean justCheck) {
        String name = player.getName();
        if (Conf.playersWhoBypassAllProtection.contains(name)) {
            return true;
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(player);
        if (me.isAdminBypassing()) {
            return true;
        }

        FLocation loc = new FLocation(location);
        Faction otherFaction = Board.getInstance().getFactionAt(loc);

        if (otherFaction.isRaidable()) {
            return true;
        }

        if (otherFaction.hasPlayersOnline()) {
            if (!Conf.territoryDenyUseageMaterials.contains(material)) {
                return true; // Item isn't one we're preventing for online factions.
            }
        } else {
            if (!Conf.territoryDenyUseageMaterialsWhenOffline.contains(material)) {
                return true; // Item isn't one we're preventing for offline factions.
            }
        }

        if (otherFaction.isWilderness()) {
            if (!Conf.wildernessDenyUseage || Conf.worldsNoWildernessProtection.contains(location.getWorld().getName())) {
                return true; // This is not faction territory. Use whatever you like here.
            }
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_WILDERNESS, TextUtil.getMaterialName(material));
            }
            return false;
        } else if (otherFaction.isSafeZone()) {
            if (!Conf.safeZoneDenyUseage || Permission.MANAGE_SAFE_ZONE.has(player)) {
                return true;
            }
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_SAFEZONE, TextUtil.getMaterialName(material));
            }
            return false;
        } else if (otherFaction.isWarZone()) {
            if (!Conf.warZoneDenyUseage || Permission.MANAGE_WAR_ZONE.has(player)) {
                return true;
            }
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_WARZONE, TextUtil.getMaterialName(material));
            }
            return false;
        }

        Faction myFaction = me.getFaction();
        Relation rel = myFaction.getRelationTo(otherFaction);

        // Cancel if we are not in our own territory
        if (rel.confDenyUseage()) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_TERRITORY, "use " + TextUtil.getMaterialName(material), otherFaction.getTag(myFaction));
            }
            return false;
        }

        // Also cancel if player doesn't have ownership rights for this claim
        if (Conf.ownedAreasEnabled && Conf.ownedAreaDenyUseage && !otherFaction.playerHasOwnershipRights(me, loc)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_OWNED, TextUtil.getMaterialName(material), otherFaction.getOwnerListString(loc));
            }
            return false;
        }

        return true;
    }

    public static boolean canPlayerUseBlock(Player player, Block block, boolean justCheck) {
        if (Conf.playersWhoBypassAllProtection.contains(player.getName())) {
            return true;
        }

        FPlayer me = FPlayers.getInstance().getByPlayer(player);
        if (me.isAdminBypassing()) {
            return true;
        }

        Material material = block.getType();
        FLocation loc = new FLocation(block);
        Faction otherFaction = Board.getInstance().getFactionAt(loc);

        // no door/chest/whatever protection in wilderness, war zones, or safe zones
        if (!otherFaction.isNormal() || otherFaction.isRaidable()) {
            return true;
        }

        // Dupe fix.
        Faction myFaction = me.getFaction();
        Relation rel = myFaction.getRelationTo(otherFaction);
        if (!rel.isMember() || !otherFaction.playerHasOwnershipRights(me, loc) && player.getItemInHand() != null) {
            switch (player.getItemInHand().getType()) {
                case CHEST:
                case SIGN_POST:
                case TRAPPED_CHEST:
                case SIGN:
                case WOOD_DOOR:
                case IRON_DOOR:
                    return false;
                default:
                    break;
            }
        }

        // We only care about some material types.
        if (otherFaction.hasPlayersOnline()) {
            if (!Conf.territoryProtectedMaterials.contains(material)) {
                return true;
            }
        } else {
            if (!Conf.territoryProtectedMaterialsWhenOffline.contains(material)) {
                return true;
            }
        }

        // You may use any block unless it is another faction's territory...
        if (rel.isNeutral() || (rel.isEnemy() && Conf.territoryEnemyProtectMaterials) || (rel.isAlly() && Conf.territoryAllyProtectMaterials) || (rel.isTruce() && Conf.territoryTruceProtectMaterials)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_TERRITORY, (material == Material.SOIL ? "trample " : "use ") + TextUtil.getMaterialName(material), otherFaction.getTag(myFaction));
            }
            return false;
        }

        // Also cancel if player doesn't have ownership rights for this claim
        if (Conf.ownedAreasEnabled && Conf.ownedAreaProtectMaterials && !otherFaction.playerHasOwnershipRights(me, loc)) {
            if (!justCheck) {
                me.msg(TL.PLAYER_USE_OWNED, TextUtil.getMaterialName(material), otherFaction.getOwnerListString(loc));
            }
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        FPlayer me = FPlayers.getInstance().getByPlayer(event.getPlayer());
        Location home = me.getFaction().getHome();
        if (Conf.homesEnabled && Conf.homesTeleportToOnDeath && home != null &&
                (Conf.homesRespawnFromNoDtrLossWorlds || !Conf.worldsNoDtrLoss.contains(event.getPlayer().getWorld().getName()))) {
            event.setRespawnLocation(home);
        }
    }

    // For some reason onPlayerInteract() sometimes misses bucket events depending on distance (something like 2-3 blocks away isn't detected),
    // but these separate bucket events below always fire without fail
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlockClicked();
        Player player = event.getPlayer();

        if (!playerCanUseItemHere(player, block.getLocation(), event.getBucket(), false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlockClicked();
        Player player = event.getPlayer();

        if (!playerCanUseItemHere(player, block.getLocation(), event.getBucket(), false)) {
            event.setCancelled(true);
        }
    }

    public static boolean preventCommand(String fullCmd, Player player) {
        if ((Conf.territoryNeutralDenyCommands.isEmpty() && Conf.territoryEnemyDenyCommands.isEmpty() && Conf.permanentFactionMemberDenyCommands.isEmpty() && Conf.warzoneDenyCommands.isEmpty())) {
            return false;
        }

        fullCmd = fullCmd.toLowerCase();

        FPlayer me = FPlayers.getInstance().getByPlayer(player);

        String shortCmd;  // command without the slash at the beginning
        if (fullCmd.startsWith("/")) {
            shortCmd = fullCmd.substring(1);
        } else {
            shortCmd = fullCmd;
            fullCmd = "/" + fullCmd;
        }

        if (me.hasFaction() && !me.isAdminBypassing() && !Conf.permanentFactionMemberDenyCommands.isEmpty() && me.getFaction().isPermanent() && isCommandInList(fullCmd, shortCmd, Conf.permanentFactionMemberDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_PERMANENT, fullCmd);
            return true;
        }

        Faction at = Board.getInstance().getFactionAt(new FLocation(player.getLocation()));
        if (at.isWilderness() && !Conf.wildernessDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.wildernessDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_WILDERNESS, fullCmd);
            return true;
        }

        Relation rel = at.getRelationTo(me);
        if (rel.isAlly() && !Conf.territoryAllyDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryAllyDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_ALLY, fullCmd);
            return false;
        }

        if (rel.isNeutral() && !Conf.territoryNeutralDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryNeutralDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_NEUTRAL, fullCmd);
            return true;
        }

        if (rel.isEnemy() && !Conf.territoryEnemyDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.territoryEnemyDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_ENEMY, fullCmd);
            return true;
        }

        if (at.isWarZone() && !Conf.warzoneDenyCommands.isEmpty() && !me.isAdminBypassing() && isCommandInList(fullCmd, shortCmd, Conf.warzoneDenyCommands.iterator())) {
            me.msg(TL.PLAYER_COMMAND_WARZONE, fullCmd);
            return true;
        }

        return false;
    }

    private static boolean isCommandInList(String fullCmd, String shortCmd, Iterator<String> iter) {
        String cmdCheck;
        while (iter.hasNext()) {
            cmdCheck = iter.next();
            if (cmdCheck == null) {
                iter.remove();
                continue;
            }

            cmdCheck = cmdCheck.toLowerCase();
            if (fullCmd.startsWith(cmdCheck) || shortCmd.startsWith(cmdCheck)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerKick(PlayerKickEvent event) {
        FPlayer badGuy = FPlayers.getInstance().getByPlayer(event.getPlayer());
        if (badGuy == null) {
            return;
        }

        // if player was banned (not just kicked), get rid of their stored info
        if (Conf.removePlayerDataWhenBanned && event.getReason().equals("Banned by admin.")) {
            if (badGuy.getRole() == Role.ADMIN) {
                badGuy.getFaction().promoteNewLeader();
            }

            badGuy.leave(false);
            badGuy.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    final public void onFactionJoin(FPlayerJoinEvent event) {
        FTeamWrapper.applyUpdatesLater(event.getFaction());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFactionLeave(FPlayerLeaveEvent event) {
        FTeamWrapper.applyUpdatesLater(event.getFaction());
    }
}

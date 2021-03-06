package com.massivecraft.factions.zcore.persist;

import com.massivecraft.factions.*;
import com.massivecraft.factions.event.DTRChangeEvent;
import com.massivecraft.factions.iface.EconomyParticipator;
import com.massivecraft.factions.iface.RelationParticipator;
import com.massivecraft.factions.integration.Econ;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Relation;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.LazyLocation;
import com.massivecraft.factions.util.MiscUtil;
import com.massivecraft.factions.util.RelationUtil;
import com.massivecraft.factions.zcore.util.TL;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public abstract class MemoryFaction implements Faction, EconomyParticipator {

    @Getter private ConcurrentHashMap<String, LazyLocation> warps = new ConcurrentHashMap<>();
    @Getter private Map<FLocation, Set<String>> claimOwnership = new ConcurrentHashMap<>();
    @Getter private Map<String, List<String>> announcements = new ConcurrentHashMap<>();
    private Map<String, Relation> relationWish = new HashMap<>();
    @Getter private Set<String> invites = new HashSet<>();
    @Getter @Setter protected String description, id;
    protected LazyLocation home;
    @Getter @Setter private boolean peaceful, open, peacefulExplosionsEnabled;
    @Getter @Setter private long foundedDate, lastDeath, lastDtrUpdateTime;
    protected String tag;
    protected boolean permanent;
    protected double money, dtr;

    protected transient Set<FPlayer> fplayers = new HashSet<>();
    private transient long lastPlayerLoggedOffTime;
    private transient boolean wasFrozen;

    public void addAnnouncement(FPlayer fPlayer, String msg) {
        List<String> list = announcements.containsKey(fPlayer.getId()) ? announcements.get(fPlayer.getId()) : new ArrayList<String>();
        list.add(msg);
        announcements.put(fPlayer.getId(), list);
    }

    public void sendUnreadAnnouncements(FPlayer fPlayer) {
        if (!announcements.containsKey(fPlayer.getId())) {
            return;
        }

        fPlayer.msg(TL.FACTION_UNREAD_ANNOUNCEMENT.toString());

        announcements.get(fPlayer.getPlayer().getUniqueId().toString()).forEach(fPlayer::sendMessage);

        fPlayer.msg(TL.FACTION_UNREAD_ANNOUNCEMENT.toString());
        announcements.remove(fPlayer.getId());
    }

    public void removeAnnouncements(FPlayer fPlayer) {
        if (announcements.containsKey(fPlayer.getId())) {
            announcements.remove(fPlayer.getId());
        }
    }

    /* Compatibility method */
    public Set<FLocation> getAllClaims() {
        return Board.getInstance().getAllClaims(this);
    }

    public LazyLocation getWarp(String name) {
        return this.warps.get(name);
    }

    public void setWarp(String name, LazyLocation loc) {
        this.warps.put(name, loc);
    }

    public boolean isWarp(String name) {
        return this.warps.containsKey(name);
    }

    public boolean removeWarp(String name) {
        return warps.remove(name) != null;
    }

    public void clearWarps() {
        warps.clear();
    }

    public void invite(FPlayer fplayer) {
        this.invites.add(fplayer.getId());
    }

    public void deinvite(FPlayer fplayer) {
        this.invites.remove(fplayer.getId());
    }

    public boolean isInvited(FPlayer fplayer) {
        return this.invites.contains(fplayer.getId());
    }

    public boolean noExplosionsInTerritory() {
        return this.peaceful && !peacefulExplosionsEnabled;
    }

    public boolean isPermanent() {
        return permanent || !this.isNormal();
    }

    public void setPermanent(boolean isPermanent) {
        permanent = isPermanent;
    }

    public String getTag() {
        return this.tag;
    }

    public String getTag(String prefix) {
        return prefix + this.tag;
    }

    public int getKills() {
        int kills = 0;
        for (FPlayer player : this.getFPlayers()) {
            kills += player.getKills();
        }
        return kills;
    }

    public int getDeaths() {
        int deaths = 0;
        for (FPlayer player : this.getFPlayers()) {
            deaths += player.getDeaths();
        }
        return deaths;
    }

    public String getTag(Faction otherFaction) {
        if (otherFaction == null) {
            return getTag();
        }
        return this.getTag(this.getColorTo(otherFaction).toString());
    }

    public String getTag(FPlayer otherFplayer) {
        if (otherFplayer == null) {
            return getTag();
        }
        return this.getTag(this.getColorTo(otherFplayer).toString());
    }

    public void setTag(String str) {
        if (Conf.factionTagForceUpperCase) {
            str = str.toUpperCase();
        }
        this.tag = str;
    }

    public String getComparisonTag() {
        return MiscUtil.getComparisonString(this.tag);
    }

    public void setHome(Location home) {
        this.home = new LazyLocation(home);
    }

    public boolean hasHome() {
        return this.getHome() != null;
    }

    public Location getHome() {
        confirmValidHome();
        return (this.home != null) ? this.home.getLocation() : null;
    }

    public void confirmValidHome() {
        if (!Conf.homesMustBeInClaimedTerritory || this.home == null || (this.home.getLocation() != null && Board.getInstance().getFactionAt(new FLocation(this.home.getLocation())) == this)) {
            return;
        }

        msg(TL.FACTION_UNSET.toString());
        this.home = null;
    }

    public String getAccountId() {
        String aid = "faction-" + this.getId();

        // We need to override the default money given to players.
        if (!Econ.hasAccount(aid)) {
            Econ.setBalance(aid, 0);
        }

        return aid;
    }

    // -------------------------------------------- //
    // Construct
    // -------------------------------------------- //
    protected MemoryFaction() {}

    public MemoryFaction(String id) {
        this.id = id;
        this.open = Conf.newFactionsDefaultOpen;
        this.tag = "???";
        this.description = TL.GENERIC_DEFAULTDESCRIPTION.toString();
        this.lastPlayerLoggedOffTime = 0;
        this.peaceful = false;
        this.peacefulExplosionsEnabled = false;
        this.permanent = false;
        this.money = 0.0;
        this.dtr = 0.0;
        long time = System.currentTimeMillis();
        this.lastDtrUpdateTime = time;
        this.foundedDate = time;
    }

    public MemoryFaction(MemoryFaction old) {
        id = old.id;
        this.dtr = old.dtr;
        this.foundedDate = old.foundedDate;
        peacefulExplosionsEnabled = old.peacefulExplosionsEnabled;
        permanent = old.permanent;
        tag = old.tag;
        description = old.description;
        open = old.open;
        peaceful = old.peaceful;
        home = old.home;
        lastPlayerLoggedOffTime = old.lastPlayerLoggedOffTime;
        money = old.money;
        relationWish = old.relationWish;
        claimOwnership = old.claimOwnership;
        fplayers = new HashSet<>();
        invites = old.invites;
        announcements = old.announcements;
    }

    // -------------------------------------------- //
    // Extra Getters And Setters
    // -------------------------------------------- //
    public boolean noPvPInTerritory() {
        return isSafeZone() || (peaceful && Conf.peacefulTerritoryDisablePVP);
    }

    public boolean noMonstersInTerritory() {
        return isSafeZone() || (peaceful && Conf.peacefulTerritoryDisableMonsters);
    }

    // -------------------------------
    // Understand the types
    // -------------------------------

    @Deprecated
    public boolean isNone() {
        return isWilderness();
    }

    public boolean isNormal() {
        return !(this.isWilderness() || this.isSafeZone() || this.isWarZone());
    }

    public boolean isWilderness() {
        return this.getId().equals("0");
    }

    public boolean isSafeZone() {
        return this.getId().equals("-1");
    }

    public boolean isWarZone() {
        return this.getId().equals("-2");
    }

    public boolean isPlayerFreeType() {
        return this.isSafeZone() || this.isWarZone();
    }

    // -------------------------------
    // Relation and relation colors
    // -------------------------------

    @Override
    public String describeTo(RelationParticipator that, boolean ucfirst) {
        return RelationUtil.describeThatToMe(this, that, ucfirst);
    }

    @Override
    public String describeTo(RelationParticipator that) {
        return RelationUtil.describeThatToMe(this, that);
    }

    @Override
    public Relation getRelationTo(RelationParticipator rp) {
        return RelationUtil.getRelationTo(this, rp);
    }

    @Override
    public Relation getRelationTo(RelationParticipator rp, boolean ignorePeaceful) {
        return RelationUtil.getRelationTo(this, rp, ignorePeaceful);
    }

    @Override
    public ChatColor getColorTo(RelationParticipator rp) {
        return RelationUtil.getColorOfThatToMe(this, rp);
    }

    public Relation getRelationWish(Faction otherFaction) {
        if (this.relationWish.containsKey(otherFaction.getId())) {
            return this.relationWish.get(otherFaction.getId());
        }

        return Relation.fromString(P.p.getConfig().getString("default-relation", "enemy")); // Always default to old behavior.
    }

    public void setRelationWish(Faction otherFaction, Relation relation) {
        if (this.relationWish.containsKey(otherFaction.getId()) && relation.equals(Relation.NEUTRAL)) {
            this.relationWish.remove(otherFaction.getId());
        } else {
            this.relationWish.put(otherFaction.getId(), relation);
        }
    }

    public int getRelationCount(Relation relation) {
        int count = 0;

        for (Faction faction : Factions.getInstance().getAllFactions()) {
            if (faction.getRelationTo(this) == relation) {
                count++;
            }
        }

        return count;
    }

    // -------------------------------
    // Land
    // -------------------------------

    public int getLand() {
        return Board.getInstance().getFactionCoordCount(this);
    }

    public int getMaxLand() {
        int landPerPlayer = P.p.getConfig().getInt("hcf.land-per-player", 6);
        int maxFactionLand = P.p.getConfig().getInt("hcf.faction-land-max", 36);
        int initialLand = P.p.getConfig().getInt("hcf.initial-land", 6);
        return isPermanent() && isNormal() ? maxFactionLand : Math.min(initialLand + ((getSize() - 1) * landPerPlayer), maxFactionLand);
    }

    public int getLandInWorld(String worldName) {
        return Board.getInstance().getFactionCoordCountInWorld(this, worldName);
    }

    // -------------------------------
    // DTR
    // -------------------------------

    public double getDTR() {
        return dtr;
    }

    public void updateDTR() {
        double toDtr = getDTR();
        double maxDtr = getMaxDTR();

        if (toDtr == maxDtr) {
            this.lastDtrUpdateTime = System.currentTimeMillis();
            return;
        }

        if (isFrozen()) {
            if (!P.p.getConfig().getBoolean("hcf.dtr.allow-background-regen", false)) {
                // if we block background-regen, then we need to fake updates
                this.lastDtrUpdateTime = System.currentTimeMillis();
            }

            wasFrozen = true;
            return;
        } else if (wasFrozen && P.p.getConfig().getBoolean("hcf.dtr.notify-unfreeze", false)) {
            wasFrozen = false;
            P.p.debug("Notified " + this.getTag() + " they are no longer frozen.");
            this.msg(TL.FACTION_UNFROZEN);
        }

        long now = System.currentTimeMillis();
        long millisPassed = now - this.lastDtrUpdateTime;

        double base = millisPassed * P.p.getConfig().getDouble("hcf.dtr.minute-dtr", 0.01D) / 60000.0D;
        double delta = getSize() > 1 ? getOnlinePlayers().size() * base : base;

        toDtr += delta;

        if (toDtr > maxDtr) {
            P.p.debug("DTR [" + toDtr + "] exceeded max of [" + maxDtr + "]");
            toDtr = maxDtr;
        } else if (toDtr < getMinDTR()) {
            P.p.debug("DTR [" + toDtr + "] exceeded min of [" + getMinDTR() + "]");
            toDtr = getMinDTR();
        }
        if (this.dtr != toDtr) {
            DTRChangeEvent changeEvent = new DTRChangeEvent(this, null, this.dtr, toDtr);
            Bukkit.getServer().getPluginManager().callEvent(changeEvent);

            if (changeEvent.isCancelled()) {
                return;
            }

            P.p.debug("Faction=[" + getTag() + "]");
            P.p.debug("From=[" + changeEvent.getFrom() + "] To=[" + changeEvent.getTo() + "]");
            P.p.debug("Change=[" + (changeEvent.getTo() - changeEvent.getFrom()) + "]");
            this.dtr = changeEvent.getTo();
            this.lastDtrUpdateTime = System.currentTimeMillis();
        }
    }

    public double getMaxDTR() {
        if (getSize() == 1) {
            return P.p.getConfig().getDouble("hcf.dtr.solo-faction", 1.02D);
        }

        return getMaxPlayerDTR() * this.fplayers.size();
    }

    public double getMinDTR() {
        return P.p.getConfig().getDouble("hcf.dtr.min-faction-dtr", -6.0);
    }

    public double getMaxPlayerDTR() {
        double maxCalc = P.p.getConfig().getDouble("hcf.dtr.max-faction-dtr", 5.5) / this.fplayers.size();
        return Math.min(P.p.getConfig().getDouble("hcf.dtr.player-dtr", 0.51), maxCalc);
    }

    public void setDTR(double dtr) {
        this.alterDTR(dtr - this.dtr);
    }

    public void alterDTR(double delta) {
        if (this.dtr + delta > this.getMaxDTR()) {
            this.dtr = this.getMaxDTR();
        } else if (this.dtr + delta < this.getMinDTR()) {
            this.dtr = this.getMinDTR();
        } else {
            this.dtr += delta;
        }
    }

    public void thaw() {
        // move lastDeath to the past so we unfreeze
        this.lastDeath -= (P.p.getConfig().getLong("hcf.dtr.dtr-freeze", 0) * 1000);
    }

    public boolean isRaidable() {
        // by default, permanent factions are not raidable
        return this.isPermanent() ? Conf.permanentFactionsAreRaidable && this.getDTR() <= 0 : this.getDTR() <= 0;
    }

    public boolean isFrozen() {
        long freezeSeconds = P.p.getConfig().getLong("hcf.dtr.dtr-freeze", 0);
        return freezeSeconds > 0 && System.currentTimeMillis() - lastDeath < freezeSeconds * 1000;
    }

    public long getFreezeLeft() {
        if (isFrozen()) {
            long freezeSeconds = P.p.getConfig().getLong("hcf.dtr.dtr-freeze", 0);
            return freezeSeconds * 1000 - (System.currentTimeMillis() - lastDeath);
        }

        return 0;
    }

    // -------------------------------
    // FPlayers
    // -------------------------------

    // maintain the reference list of FPlayers in this faction
    public void refreshFPlayers() {
        fplayers.clear();

        if (this.isPlayerFreeType()) {
            return;
        }

        for (FPlayer fplayer : FPlayers.getInstance().getAllFPlayers()) {
            if (fplayer.getFactionId().equalsIgnoreCase(id)) {
                fplayers.add(fplayer);
            }
        }
    }

    public boolean addFPlayer(FPlayer fplayer) {
        return !this.isPlayerFreeType() && fplayers.add(fplayer);
    }

    public boolean removeFPlayer(FPlayer fplayer) {
        return !this.isPlayerFreeType() && fplayers.remove(fplayer);
    }

    public int getSize() {
        return fplayers.size();
    }

    public Set<FPlayer> getFPlayers() {
        // return a shallow copy of the FPlayer list, to prevent tampering and
        // concurrency issues
        return new HashSet<>(fplayers);
    }

    public Set<FPlayer> getFPlayersWhereOnline(boolean online) {
        Set<FPlayer> ret = new HashSet<>();

        if (!this.isNormal()) {
            return ret;
        }

        ret.addAll(fplayers.stream().filter(fplayer -> fplayer.isOnline() == online).collect(Collectors.toList()));

        return ret;
    }

    public FPlayer getFPlayerAdmin() {
        if (!this.isNormal()) {
            return null;
        }

        for (FPlayer fplayer : fplayers) {
            if (fplayer.getRole() == Role.ADMIN) {
                return fplayer;
            }
        }

        return null;
    }

    public ArrayList<FPlayer> getFPlayersWhereRole(Role role) {
        ArrayList<FPlayer> ret = new ArrayList<>();

        if (!this.isNormal()) {
            return ret;
        }

        for (FPlayer fplayer : fplayers) {
            if (fplayer.getRole() == role) {
                ret.add(fplayer);
            }
        }

        return ret;
    }

    public ArrayList<Player> getOnlinePlayers() {
        ArrayList<Player> ret = new ArrayList<>();

        if (this.isPlayerFreeType()) {
            return ret;
        }

        for (Player player : P.p.getServer().getOnlinePlayers()) {
            FPlayer fplayer = FPlayers.getInstance().getByPlayer(player);

            if (fplayer.getFaction() == this) {
                ret.add(player);
            }
        }

        return ret;
    }

    // slightly faster check than getOnlinePlayers() if you just want to see if
    // there are any players online
    public boolean hasPlayersOnline() {
        // only real factions can have players online, not safe zone / war zone
        if (this.isPlayerFreeType()) {
            return false;
        }

        for (Player player : P.p.getServer().getOnlinePlayers()) {
            FPlayer fplayer = FPlayers.getInstance().getByPlayer(player);

            if (fplayer != null && fplayer.getFaction() == this) {
                return true;
            }
        }

        // even if all players are technically logged off, maybe someone was on
        // recently enough to not consider them officially offline yet
        return Conf.considerFactionsReallyOfflineAfterXMinutes > 0 && System.currentTimeMillis() < lastPlayerLoggedOffTime + (Conf.considerFactionsReallyOfflineAfterXMinutes * 60000);
    }

    public void memberLoggedOff() {
        if (this.isNormal()) {
            lastPlayerLoggedOffTime = System.currentTimeMillis();
        }
    }

    // used when current leader is about to be removed from the faction;
    // promotes new leader, or disbands faction if no other members left
    public void promoteNewLeader() {
        if (!this.isNormal()) {
            return;
        }
        if (this.isPermanent() && Conf.permanentFactionsDisableLeaderPromotion) {
            return;
        }

        FPlayer oldLeader = this.getFPlayerAdmin();

        // get list of moderators, or list of normal members if there are no moderators
        ArrayList<FPlayer> replacements = this.getFPlayersWhereRole(Role.MODERATOR);

        if (replacements == null || replacements.isEmpty()) {
            replacements = this.getFPlayersWhereRole(Role.NORMAL);
        }

        if (replacements == null || replacements.isEmpty()) { // faction admin  is the only  member; one-man  faction
            if (this.isPermanent()) {
                if (oldLeader != null) {
                    oldLeader.setRole(Role.NORMAL);
                }

                return;
            }

            // no members left and faction isn't permanent, so disband it
            if (Conf.logFactionDisband) {
                P.p.log("The faction " + this.getTag() + " (" + this.getId() + ") has been disbanded since it has no members left.");
            }

            for (FPlayer fPlayer : FPlayers.getInstance().getOnlinePlayers()) {
                msg(TL.FACTION_DISBANDED, this.getTag(fPlayer));
            }

            Factions.getInstance().removeFaction(getId());
        } else { // promote new faction admin
            if (oldLeader != null) {
                oldLeader.setRole(Role.NORMAL);
            }

            replacements.get(0).setRole(Role.ADMIN);
            msg(TL.FACTION_ADMIN_CHANGE, oldLeader == null ? "" : oldLeader.getName(), replacements.get(0).getName());
            P.p.log("Faction " + this.getTag() + " (" + this.getId() + ") admin was removed. Replacement admin: " + replacements.get(0).getName());
        }
    }

    // ----------------------------------------------//
    // Messages
    // ----------------------------------------------//
    public void msg(String message, Object... args) {
        message = P.p.txt.parse(message, args);

        for (FPlayer fplayer : this.getFPlayersWhereOnline(true)) {
            fplayer.sendMessage(message);
        }
    }

    public void msg(TL translation, Object... args) {
        msg(translation.toString(), args);
    }

    public void sendMessage(String message) {
        for (FPlayer fplayer : this.getFPlayersWhereOnline(true)) {
            fplayer.sendMessage(message);
        }
    }

    public void sendMessage(List<String> messages) {
        for (FPlayer fplayer : this.getFPlayersWhereOnline(true)) {
            fplayer.sendMessage(messages);
        }
    }

    // ----------------------------------------------//
    // Ownership of specific claims
    // ----------------------------------------------//

    public void clearAllClaimOwnership() {
        claimOwnership.clear();
    }

    public void clearClaimOwnership(FLocation loc) {
        claimOwnership.remove(loc);
    }

    public void clearClaimOwnership(FPlayer player) {
        if (id == null || id.isEmpty()) {
            return;
        }

        Set<String> ownerData;

        for (Entry<FLocation, Set<String>> entry : claimOwnership.entrySet()) {
            ownerData = entry.getValue();

            if (ownerData == null) {
                continue;
            }

            Iterator<String> iter = ownerData.iterator();

            while (iter.hasNext()) {
                if (iter.next().equals(player.getId())) {
                    iter.remove();
                }
            }

            if (ownerData.isEmpty()) {
                claimOwnership.remove(entry.getKey());
            }
        }
    }

    public int getCountOfClaimsWithOwners() {
        return claimOwnership.isEmpty() ? 0 : claimOwnership.size();
    }

    public boolean doesLocationHaveOwnersSet(FLocation loc) {
        if (claimOwnership.isEmpty() || !claimOwnership.containsKey(loc)) {
            return false;
        }

        Set<String> ownerData = claimOwnership.get(loc);
        return ownerData != null && !ownerData.isEmpty();
    }

    public boolean isPlayerInOwnerList(FPlayer player, FLocation loc) {
        if (claimOwnership.isEmpty()) {
            return false;
        }

        Set<String> ownerData = claimOwnership.get(loc);
        return ownerData != null && ownerData.contains(player.getId());
    }

    public void setPlayerAsOwner(FPlayer player, FLocation loc) {
        Set<String> ownerData = claimOwnership.get(loc);

        if (ownerData == null) {
            ownerData = new HashSet<>();
        }

        ownerData.add(player.getId());
        claimOwnership.put(loc, ownerData);
    }

    public void removePlayerAsOwner(FPlayer player, FLocation loc) {
        Set<String> ownerData = claimOwnership.get(loc);

        if (ownerData == null) {
            return;
        }

        ownerData.remove(player.getId());
        claimOwnership.put(loc, ownerData);
    }

    public Set<String> getOwnerList(FLocation loc) {
        return claimOwnership.get(loc);
    }

    public String getOwnerListString(FLocation loc) {
        Set<String> ownerData = claimOwnership.get(loc);

        if (ownerData == null || ownerData.isEmpty()) {
            return "";
        }

        String ownerList = "";

        for (String anOwnerData : ownerData) {
            if (!ownerList.isEmpty()) {
                ownerList += ", ";
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(UUID.fromString(anOwnerData));
            ownerList += offlinePlayer != null ? offlinePlayer.getName() : TL.GENERIC_NULLPLAYER.toString();
        }

        return ownerList;
    }

    public boolean playerHasOwnershipRights(FPlayer fplayer, FLocation loc) {
        // in own faction, with sufficient role or permission to bypass
        // ownership?
        if (fplayer.getFaction() == this && (fplayer.getRole().isAtLeast(Conf.ownedAreaModeratorsBypass ? Role.MODERATOR : Role.ADMIN) || Permission.OWNERSHIP_BYPASS.has(fplayer.getPlayer()))) {
            return true;
        }

        // make sure claimOwnership is initialized
        if (claimOwnership.isEmpty()) {
            return true;
        }

        // need to check the ownership list, then
        Set<String> ownerData = claimOwnership.get(loc);

        // if no owner list, owner list is empty, or player is in owner list,
        // they're allowed
        return ownerData == null || ownerData.isEmpty() || ownerData.contains(fplayer.getId());
    }

    // ----------------------------------------------//
    // Persistance and entity management
    // ----------------------------------------------//
    public void remove() {
        if (Econ.shouldBeUsed()) {
            Econ.setBalance(getAccountId(), 0);
        }

        // Clean the board
        ((MemoryBoard) Board.getInstance()).clean(id);

        for (FPlayer fPlayer : fplayers) {
            fPlayer.resetFactionData(false);
        }
    }
}

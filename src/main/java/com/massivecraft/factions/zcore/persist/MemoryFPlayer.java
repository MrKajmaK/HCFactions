package com.massivecraft.factions.zcore.persist;

import com.massivecraft.factions.*;
import com.massivecraft.factions.event.FPlayerLeaveEvent;
import com.massivecraft.factions.event.LandClaimEvent;
import com.massivecraft.factions.iface.EconomyParticipator;
import com.massivecraft.factions.iface.RelationParticipator;
import com.massivecraft.factions.integration.Econ;
import com.massivecraft.factions.integration.Essentials;
import com.massivecraft.factions.integration.Worldguard;
import com.massivecraft.factions.scoreboards.FScoreboard;
import com.massivecraft.factions.scoreboards.sidebar.FInfoSidebar;
import com.massivecraft.factions.struct.ChatMode;
import com.massivecraft.factions.struct.Permission;
import com.massivecraft.factions.struct.Relation;
import com.massivecraft.factions.struct.Role;
import com.massivecraft.factions.util.RelationUtil;
import com.massivecraft.factions.zcore.util.TL;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Logged in players always have exactly one FPlayer instance. Logged out players may or may not have an FPlayer
 * instance. They will always have one if they are part of a faction. This is because only players with a faction are
 * saved to disk (in order to not waste disk space).
 * <p>
 * The FPlayer is linked to a minecraft player using the player name.
 * <p>
 * The same instance is always returned for the same player. This means you can use the == operator. No .equals method
 * necessary.
 */

public abstract class MemoryFPlayer implements FPlayer {

    private String id;
    private ChatMode chatMode;
    private int kills, deaths;

    @Getter private String factionId;
    @Setter private String title;
    @Setter private String name;
    @Getter @Setter private Role role;
    @Getter @Setter private boolean monitoringJoins;
    @Getter @Setter private boolean spyingChat;
    @Getter @Setter private long lastLoginTime;
    @Getter @Setter private long lastCombatTime;

    /* Transient fields not serialized to file */
    private transient UUID uuid;
    @Getter private transient Faction autoClaimFor;
    @Getter private transient boolean autoSafeZoneEnabled;
    @Getter private transient boolean autoWarZoneEnabled;
    @Getter private transient boolean isAdminBypassing;
    @Getter @Setter private transient boolean mapAutoUpdating;
    @Getter @Setter private transient boolean loginPvpDisabled;
    @Getter @Setter private transient boolean online;
    @Getter @Setter private transient FLocation lastStoodAt = new FLocation();

    public Faction getFaction() {
        if (this.factionId == null) {
            this.factionId = "0";
        }
        return Factions.getInstance().getFactionById(this.factionId);
    }

    public boolean hasFaction() {
        return !factionId.equals("0");
    }

    public void setFaction(Faction faction) {
        Faction oldFaction = this.getFaction();
        if (oldFaction != null) {
            oldFaction.removeFPlayer(this);
        }

        faction.addFPlayer(this);
        this.factionId = faction.getId();
    }

    public void setAutoClaimFor(Faction faction) {
        this.autoClaimFor = faction;
        if (this.autoClaimFor != null) {
            // TODO: merge these into same autoclaim
            this.autoSafeZoneEnabled = false;
            this.autoWarZoneEnabled = false;
        }
    }

    @Override
    public boolean isAutoSafeClaimEnabled() {
        return this.autoSafeZoneEnabled;
    }

    public void setIsAutoSafeClaimEnabled(boolean enabled) {
        this.autoSafeZoneEnabled = enabled;
        if (enabled) {
            this.autoClaimFor = null;
            this.autoWarZoneEnabled = false;
        }
    }

    @Override
    public boolean isAutoWarClaimEnabled() {
        return this.autoWarZoneEnabled;
    }

    public void setIsAutoWarClaimEnabled(boolean enabled) {
        this.autoWarZoneEnabled = enabled;
        if (enabled) {
            this.autoClaimFor = null;
            this.autoSafeZoneEnabled = false;
        }
    }

    public boolean isVanished(FPlayer viewer) {
        final Player me = getPlayer();
        return Essentials.isVanished(me) || viewer.getPlayer().canSee(me);
    }

    @Override
    public void setIsAdminBypassing(boolean val) {
        this.isAdminBypassing = val;
    }

    @Override
    public void setChatMode(ChatMode chatMode) {
        this.chatMode = chatMode;
    }

    public ChatMode getChatMode() {
        if (this.factionId.equals("0") || !Conf.factionOnlyChat) {
            this.chatMode = ChatMode.PUBLIC;
        }
        return chatMode;
    }

    public String getAccountId() {
        return this.getId();
    }

    public MemoryFPlayer(String id) {
        this.id = id;
        this.resetFactionData(false);
        this.lastLoginTime = System.currentTimeMillis();
        this.mapAutoUpdating = false;
        this.autoClaimFor = null;
        this.kills = 0;
        this.deaths = 0;
        this.autoSafeZoneEnabled = false;
        this.autoWarZoneEnabled = false;
        this.monitoringJoins = true;
        this.loginPvpDisabled = Conf.noPVPDamageToOthersForXSecondsAfterLogin > 0;

        if (!Conf.newPlayerStartingFactionID.equals("0") && Factions.getInstance().isValidFactionId(Conf.newPlayerStartingFactionID)) {
            this.factionId = Conf.newPlayerStartingFactionID;
        }
    }

    public MemoryFPlayer(MemoryFPlayer other) {
        this.factionId = other.factionId;
        this.id = other.id;
        this.kills = other.kills;
        this.deaths = other.deaths;
        this.lastLoginTime = other.lastLoginTime;
        this.mapAutoUpdating = other.mapAutoUpdating;
        this.autoClaimFor = other.autoClaimFor;
        this.autoSafeZoneEnabled = other.autoSafeZoneEnabled;
        this.autoWarZoneEnabled = other.autoWarZoneEnabled;
        this.monitoringJoins = other.monitoringJoins;
        this.loginPvpDisabled = other.loginPvpDisabled;
        this.role = other.role;
        this.title = other.title;
        this.chatMode = other.chatMode;
        this.spyingChat = other.spyingChat;
        this.lastStoodAt = other.lastStoodAt;
        this.isAdminBypassing = other.isAdminBypassing;
    }

    public void saveStats(Player player) {
        if (player == null) {
            return;
        }

        this.kills = player.getStatistic(Statistic.PLAYER_KILLS);
        this.deaths = player.getStatistic(Statistic.DEATHS);
    }

    public void resetFactionData(boolean doSpoutUpdate) {
        // clean up any territory ownership in old faction, if there is one
        if (factionId != null && Factions.getInstance().isValidFactionId(this.getFactionId())) {
            Faction currentFaction = this.getFaction();
            currentFaction.removeFPlayer(this);
            if (currentFaction.isNormal()) {
                currentFaction.clearClaimOwnership(this);
            }
        }
        this.factionId = "0"; // The default neutral faction
        this.chatMode = ChatMode.PUBLIC;
        this.role = Role.NORMAL;
        this.title = "";
        this.autoClaimFor = null;
    }

    public void resetFactionData() {
        this.resetFactionData(true);
    }

    // -------------------------------------------- //
    // Getters And Setters
    // -------------------------------------------- //

    public boolean hasLoginPvpDisabled() {
        if (!loginPvpDisabled) {
            return false;
        }
        if (this.lastLoginTime + (Conf.noPVPDamageToOthersForXSecondsAfterLogin * 1000) < System.currentTimeMillis()) {
            this.loginPvpDisabled = false;
            return false;
        }
        return true;
    }

    /* Avoid new object allocation, reuse existing object and update all fields */
    public void setLastStoodAt(String world, int x, int z) {
        if (this.lastStoodAt == null) {
            this.lastStoodAt = new FLocation();
        }

        this.lastStoodAt.setWorldName(world);
        this.lastStoodAt.setX(x);
        this.lastStoodAt.setZ(z);
    }

    //----------------------------------------------//
    // Title, Name, Faction Tag and Chat
    //----------------------------------------------//

    // Base:

    public String getTitle() {
        return this.hasFaction() ? title : "";
    }

    public String getName() {
        if (this.name == null) {
            // Older versions of FactionsUUID don't save the name,
            // so `name` will be null the first time it's retrieved
            // after updating
            OfflinePlayer offline = Bukkit.getOfflinePlayer(UUID.fromString(getId()));
            this.name = offline.getName() != null ? offline.getName() : getId();
        }
        return name;
    }

    public String getTag() {
        return this.hasFaction() ? this.getFaction().getTag() : "";
    }

    public int getKills() {
        return this.isOffline() ? this.kills : this.getPlayer().getStatistic(Statistic.PLAYER_KILLS);
    }

    public int getDeaths() {
        return this.isOffline() ? this.deaths : this.getPlayer().getStatistic(Statistic.DEATHS);
    }

    // Base concatenations:

    public String getNameAndSomething(String something) {
        String ret = this.role.getPrefix();
        if (something.length() > 0) {
            ret += something + " ";
        }
        ret += this.getName();
        return ret;
    }

    public String getNameAndTitle() {
        return this.getNameAndSomething(this.getTitle());
    }

    public String getNameAndTag() {
        return this.getNameAndSomething(this.getTag());
    }

    // Colored concatenations:
    // These are used in information messages

    public String getNameAndTitle(Faction faction) {
        return this.getColorTo(faction) + this.getNameAndTitle();
    }

    public String getNameAndTitle(MemoryFPlayer fplayer) {
        return this.getColorTo(fplayer) + this.getNameAndTitle();
    }

    // Chat Tag:
    // These are injected into the format of global chat messages.

    public String getChatTag() {
        String tag = this.hasFaction() ? this.getRole().getChatPrefix() + this.getTag() : Conf.chatTagFactionlessSymbol;
        return String.format(Conf.chatTagFormat, tag);
    }

    // Colored Chat Tag
    public String getChatTag(Faction faction) {
        return this.getRelationTo(faction).getColor() + getChatTag();
    }

    @Override
    public String getChatTag(FPlayer fplayer) {
        return this.getColorTo(fplayer) + getChatTag();
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

    public Relation getRelationToLocation() {
        return Board.getInstance().getFactionAt(new FLocation(this)).getRelationTo(this);
    }

    @Override
    public ChatColor getColorTo(RelationParticipator rp) {
        return RelationUtil.getColorOfThatToMe(this, rp);
    }

    //----------------------------------------------//
    // Health
    //----------------------------------------------//
    public void heal(int amount) {
        if (this.getPlayer() == null) {
            return;
        }

        this.getPlayer().setHealth(this.getPlayer().getHealth() + amount);
    }

    public void onDeath(String world) {
        // Only update DTR if player is in a faction
        if (hasFaction()) {
            double dtrLoss = P.p.getConfig().getDouble("hcf.dtr.death-dtr.default", 1.0);
            if (P.p.getConfig().contains("hcf.dtr.death-dtr." + world.toLowerCase())) {
                dtrLoss = P.p.getConfig().getDouble("hcf.dtr.death-dtr." + world.toLowerCase());
            }
            this.getFaction().alterDTR(-dtrLoss);
            this.getFaction().setLastDeath(System.currentTimeMillis());
        }
    }

    //----------------------------------------------//
    // Territory
    //----------------------------------------------//
    public boolean isInOwnTerritory() {
        return Board.getInstance().getFactionAt(new FLocation(this)) == this.getFaction();
    }

    public boolean isInOthersTerritory() {
        Faction factionHere = Board.getInstance().getFactionAt(new FLocation(this));
        return factionHere != null && factionHere.isNormal() && factionHere != this.getFaction();
    }

    public boolean isInAllyTerritory() {
        return Board.getInstance().getFactionAt(new FLocation(this)).getRelationTo(this).isAlly();
    }

    public boolean isInNeutralTerritory() {
        return Board.getInstance().getFactionAt(new FLocation(this)).getRelationTo(this).isNeutral();
    }

    public boolean isInEnemyTerritory() {
        return Board.getInstance().getFactionAt(new FLocation(this)).getRelationTo(this).isEnemy();
    }

    public void sendFactionHereMessage(Faction from) {
        Faction toShow = Board.getInstance().getFactionAt(getLastStoodAt());
        boolean showChat = true;
        if (shouldShowScoreboard(toShow)) {
            FScoreboard.get(this).setTemporarySidebar(new FInfoSidebar(toShow));
            showChat = P.p.getConfig().getBoolean("scoreboard.also-send-chat", false);
        }
        if (showChat) {
            this.sendMessage(P.p.txt.parse(TL.FACTION_LEAVE.format(from.getTag(this), toShow.getTag(this))));
        }
    }

    /**
     * Check if the scoreboard should be shown. Simple method to be used by above method.
     *
     * @param toShow Faction to be shown.
     * @return true if should show, otherwise false.
     */
    public boolean shouldShowScoreboard(Faction toShow) {
        return !toShow.isWarZone() && !toShow.isWilderness() && !toShow.isSafeZone() && P.p.getConfig().contains("scoreboard.finfo") && P.p.getConfig().getBoolean("scoreboard.finfo-enabled", false) && P.p.cmdBase.cmdSB.showBoard(this) && FScoreboard.get(this) != null;
    }

    // -------------------------------
    // Actions
    // -------------------------------

    public void leave(boolean makePay) {
        Faction myFaction = this.getFaction();
        makePay = makePay && Econ.shouldBeUsed() && !this.isAdminBypassing();

        if (myFaction == null) {
            resetFactionData();
            return;
        }

        boolean perm = myFaction.isPermanent();

        if (!perm && this.getRole() == Role.ADMIN && myFaction.getFPlayers().size() > 1) {
            msg(TL.LEAVE_PASSADMIN);
            return;
        }

        // if economy is enabled and they're not on the bypass list, make sure they can pay
        if (makePay && !Econ.hasAtLeast(this, Conf.econCostLeave, TL.LEAVE_TOLEAVE.toString())) {
            return;
        }

        // prevent player from leaving a frozen faction if enabled
        if (!P.p.getConfig().getBoolean("hcf.dtr.freeze-leave", false) && myFaction.isFrozen()) {
            msg(TL.LEAVE_DENY_FROZEN);
            return;
        }

        // prevent player leaving during combat
        int cooldown = P.p.getConfig().getInt("hcf.dtr.leave-cooldown", 0);
        if (cooldown > 0 && System.currentTimeMillis() - getLastCombatTime() < (cooldown * 1000)) {
            msg(TL.LEAVE_DENY_COOLING.toString());
            return;
        }

        FPlayerLeaveEvent leaveEvent = new FPlayerLeaveEvent(this, myFaction, FPlayerLeaveEvent.PlayerLeaveReason.LEAVE);
        Bukkit.getServer().getPluginManager().callEvent(leaveEvent);
        if (leaveEvent.isCancelled()) {
            return;
        }

        // then make 'em pay (if applicable)
        if (makePay && !Econ.modifyMoney(this, -Conf.econCostLeave, TL.LEAVE_TOLEAVE.toString(), TL.LEAVE_FORLEAVE.toString())) {
            return;
        }

        // Am I the last one in the faction?
        if (myFaction.getFPlayers().size() == 1) {
            // Transfer all money
            if (Econ.shouldBeUsed()) {
                Econ.transferMoney(this, myFaction, this, Econ.getBalance(myFaction.getAccountId()));
            }
        }

        if (myFaction.isNormal()) {
            for (FPlayer fplayer : myFaction.getFPlayersWhereOnline(true)) {
                fplayer.msg(TL.LEAVE_LEFT, this.describeTo(fplayer, true), myFaction.describeTo(fplayer));
            }

            if (Conf.logFactionLeave) {
                P.p.log(this.getName() + " left faction " + myFaction.getTag());
            }
        }

        myFaction.removeAnnouncements(this);
        this.resetFactionData();

        if (myFaction.isNormal() && !perm && myFaction.getFPlayers().isEmpty()) {
            // Remove this faction
            for (FPlayer fplayer : FPlayers.getInstance().getOnlinePlayers()) {
                fplayer.msg(TL.LEAVE_DISBANDED, myFaction.describeTo(fplayer, true));
            }

            Factions.getInstance().removeFaction(myFaction.getId());
            if (Conf.logFactionDisband) {
                P.p.log(TL.LEAVE_DISBANDEDLOG.format(myFaction.getTag(), myFaction.getId(), this.getName()));
            }
        }
    }

    public boolean canClaimForFaction(Faction forFaction) {
        return !forFaction.isWilderness() && (this.isAdminBypassing() || (forFaction == this.getFaction() && this.getRole().isAtLeast(Role.MODERATOR)) || (forFaction.isSafeZone() && Permission.MANAGE_SAFE_ZONE.has(getPlayer())) || (forFaction.isWarZone() && Permission.MANAGE_WAR_ZONE.has(getPlayer())));
    }

    public boolean canClaimForFactionAtLocation(Faction forFaction, Location location, boolean notifyFailure) {
        String error = null;
        FLocation flocation = new FLocation(location);
        Faction myFaction = getFaction();
        Faction currentFaction = Board.getInstance().getFactionAt(flocation);
        int ownedLand = forFaction.getLand();
        int factionBuffer = P.p.getConfig().getInt("hcf.buffer-zone", 0);
        int worldBuffer = P.p.getConfig().getInt("world-border.buffer", 0);

        if (Conf.worldGuardChecking && Worldguard.checkForRegionsInChunk(location)) {
            // Checks for WorldGuard regions in the chunk attempting to be claimed
            error = P.p.txt.parse(TL.CLAIM_PROTECTED.toString());
        } else if (Conf.worldsNoClaiming.contains(flocation.getWorldName())) {
            error = P.p.txt.parse(TL.CLAIM_DISABLED.toString());
        } else if (this.isAdminBypassing()) {
            return true;
        } else if (forFaction.isSafeZone() && Permission.MANAGE_SAFE_ZONE.has(getPlayer())) {
            return true;
        } else if (forFaction.isWarZone() && Permission.MANAGE_WAR_ZONE.has(getPlayer())) {
            return true;
        } else if (myFaction != forFaction) {
            error = P.p.txt.parse(TL.CLAIM_CANTCLAIM.toString(), forFaction.describeTo(this));
        } else if (forFaction == currentFaction) {
            error = P.p.txt.parse(TL.CLAIM_ALREADYOWN.toString(), forFaction.describeTo(this, true));
        } else if (this.getRole().value < Role.MODERATOR.value) {
            error = P.p.txt.parse(TL.CLAIM_MUSTBE.toString(), Role.MODERATOR.getTranslation());
        } else if (forFaction.getFPlayers().size() < Conf.claimsRequireMinFactionMembers) {
            error = P.p.txt.parse(TL.CLAIM_MEMBERS.toString(), Conf.claimsRequireMinFactionMembers);
        } else if (currentFaction.isSafeZone()) {
            error = P.p.txt.parse(TL.CLAIM_SAFEZONE.toString());
        } else if (currentFaction.isWarZone()) {
            error = P.p.txt.parse(TL.CLAIM_WARZONE.toString());
        } else if (ownedLand >= myFaction.getMaxLand() && forFaction.isNormal()) {
            error = P.p.txt.parse(TL.CLAIM_LIMIT.toString());
        } else if (currentFaction.getRelationTo(forFaction) == Relation.ALLY) {
            error = P.p.txt.parse(TL.CLAIM_ALLY.toString());
        } else if (Conf.claimsMustBeConnected && !this.isAdminBypassing() && myFaction.getLandInWorld(flocation.getWorldName()) > 0 && !Board.getInstance().isConnectedLocation(flocation, myFaction) && (!Conf.claimsCanBeUnconnectedIfOwnedByOtherFaction || !currentFaction.isNormal())) {
            if (Conf.claimsCanBeUnconnectedIfOwnedByOtherFaction) {
                error = P.p.txt.parse(TL.CLAIM_CONTIGIOUS.toString());
            } else {
                error = P.p.txt.parse(TL.CLAIM_FACTIONCONTIGUOUS.toString());
            }
        } else if (factionBuffer > 0 && Board.getInstance().hasFactionWithin(flocation, myFaction, factionBuffer)) {
            error = P.p.txt.parse(TL.CLAIM_TOOCLOSETOOTHERFACTION.format(factionBuffer));
        } else if (flocation.isOutsideWorldBorder(worldBuffer)) {
            if (worldBuffer > 0) {
                error = P.p.txt.parse(TL.CLAIM_OUTSIDEBORDERBUFFER.format(worldBuffer));
            } else {
                error = P.p.txt.parse(TL.CLAIM_OUTSIDEWORLDBORDER.toString());
            }
        } else if (currentFaction.isNormal()) {
            if (!Board.getInstance().isBorderLocation(flocation)) {
                error = P.p.txt.parse(TL.CLAIM_BORDER.toString());
            } else {
                error = P.p.txt.parse(TL.CLAIM_NOOVERCLAIM.format(currentFaction.getTag()));
            }
        }
        // TODO: Add more else if statements.

        if (notifyFailure && error != null) {
            msg(error);
        }
        return error == null;
    }

    public boolean attemptClaim(Faction forFaction, Location location, boolean notifyFailure) {
        return attemptClaim(forFaction, location, notifyFailure, true); // notify by default
    }

    public boolean attemptClaim(Faction forFaction, Location location, boolean notifyFailure, boolean notifySuccess) {
        // notifyFailure is false if called by auto-claim; no need to notify on every failure for it
        // return value is false on failure, true on success

        FLocation flocation = new FLocation(location);
        Faction currentFaction = Board.getInstance().getFactionAt(flocation);

        int ownedLand = forFaction.getLand();

        if (!this.canClaimForFactionAtLocation(forFaction, location, notifyFailure)) {
            return false;
        }

        // if economy is enabled and they're not on the bypass list, make sure they can pay
        boolean mustPay = Econ.shouldBeUsed() && !this.isAdminBypassing() && !forFaction.isSafeZone() && !forFaction.isWarZone();
        double cost = 0.0;
        EconomyParticipator payee = null;
        if (mustPay) {
            cost = Econ.calculateClaimCost(ownedLand, currentFaction.isNormal());

            if (Conf.econClaimUnconnectedFee != 0.0 && forFaction.getLandInWorld(flocation.getWorldName()) > 0 && !Board.getInstance().isConnectedLocation(flocation, forFaction)) {
                cost += Conf.econClaimUnconnectedFee;
            }

            if (Conf.bankEnabled && Conf.bankFactionPaysLandCosts && this.hasFaction()) {
                payee = this.getFaction();
            } else {
                payee = this;
            }

            if (!Econ.hasAtLeast(payee, cost, TL.CLAIM_TOCLAIM.toString())) {
                return false;
            }
        }

        LandClaimEvent claimEvent = new LandClaimEvent(flocation, forFaction, this);
        Bukkit.getServer().getPluginManager().callEvent(claimEvent);
        if (claimEvent.isCancelled()) {
            return false;
        }

        // then make 'em pay (if applicable)
        if (mustPay && !Econ.modifyMoney(payee, -cost, TL.CLAIM_TOCLAIM.toString(), TL.CLAIM_FORCLAIM.toString())) {
            return false;
        }

        if (notifySuccess) {
            // announce success
            Set<FPlayer> informTheseFPlayers = new HashSet<FPlayer>();
            informTheseFPlayers.add(this);
            informTheseFPlayers.addAll(forFaction.getFPlayersWhereOnline(true));
            for (FPlayer fp : informTheseFPlayers) {
                fp.msg(TL.CLAIM_CLAIMED, this.describeTo(fp, true), forFaction.describeTo(fp), currentFaction.describeTo(fp));
            }
        }

        Board.getInstance().setFactionAt(forFaction, flocation);

        if (Conf.logLandClaims) {
            P.p.log(TL.CLAIM_CLAIMEDLOG.toString(), this.getName(), flocation.getCoordString(), forFaction.getTag());
        }

        return true;
    }

    public boolean shouldBeSaved() {
        return this.hasFaction();
    }

    public void msg(String str, Object... args) {
        this.sendMessage(P.p.txt.parse(str, args));
    }

    public void msg(TL translation, Object... args) {
        this.msg(translation.toString(), args);
    }

    public UUID getUUID() {
        if (this.uuid == null) {
            this.uuid = UUID.fromString(this.id);
        }

        return uuid;
    }

    public Player getPlayer() {
        return Bukkit.getPlayer(this.getUUID());
    }

    public Set<FPlayer> getFPlayers() {
        return Collections.singleton(this);
    }

    // make sure target player should be able to detect that this player is online
    public boolean isOnlineAndVisibleTo(Player player) {
        Player target = this.getPlayer();
        return target != null && player.canSee(target);
    }

    public boolean isOffline() {
        return !isOnline();
    }

    // -------------------------------------------- //
    // Message Sending Helpers
    // -------------------------------------------- //

    public void sendMessage(String msg) {
        if (msg.contains("{null}")) {
            return; // user wants this message to not send
        }
        if (msg.contains("/n/")) {
            for (String s : msg.split("/n/")) {
                sendMessage(s);
            }
            return;
        }
        Player player = this.getPlayer();

        if (player == null) {
            return;
        }

        player.sendMessage(msg);
    }

    public void sendMessage(List<String> msgs) {
        for (String msg : msgs) {
            this.sendMessage(msg);
        }
    }

    public String getNameAndTitle(FPlayer fplayer) {
        return this.getColorTo(fplayer) + this.getNameAndTitle();
    }

    @Override
    public String getId() {
        return id;
    }

    public abstract void remove();

    @Override
    public void setId(String id) {
        this.id = id;
    }
}

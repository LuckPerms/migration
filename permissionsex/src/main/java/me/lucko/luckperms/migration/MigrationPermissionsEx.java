/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.migration;

import com.google.common.base.Strings;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.DefaultContextKeys;
import net.luckperms.api.model.PermissionHolder;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.types.InheritanceNode;
import net.luckperms.api.node.types.MetaNode;
import net.luckperms.api.node.types.PrefixNode;
import net.luckperms.api.node.types.SuffixNode;
import net.luckperms.api.track.Track;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import ru.tehkode.permissions.NativeInterface;
import ru.tehkode.permissions.PermissionEntity;
import ru.tehkode.permissions.PermissionGroup;
import ru.tehkode.permissions.PermissionManager;
import ru.tehkode.permissions.PermissionUser;
import ru.tehkode.permissions.PermissionsData;
import ru.tehkode.permissions.bukkit.PermissionsEx;
import ru.tehkode.permissions.events.PermissionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public final class MigrationPermissionsEx extends JavaPlugin {
    private LuckPerms luckPerms;
    private PermissionsEx pex;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.pex = JavaPlugin.getPlugin(PermissionsEx.class);
    }

    private void log(CommandSender sender, String msg) {
        getLogger().info(msg);
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("[migration] " + msg);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        log(sender, "Starting.");

        if (!getServer().getPluginManager().isPluginEnabled("PermissionsEx")) {
            log(sender, "Plugin not loaded.");
            return true;
        }

        PermissionManager manager = this.pex.getPermissionsManager();

        // hack to work around accessing pex async
        try {
            disablePexEvents(manager);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }

        log(sender, "Calculating group weightings.");
        int i = 0;
        for (PermissionGroup group : manager.getGroupList()) {
            i = Math.max(i, group.getRank());
        }
        int maxWeight = i + 5;

        // Migrate all groups.
        log(sender, "Starting group migration.");
        AtomicInteger groupCount = new AtomicInteger(0);
        Set<String> ladders = new HashSet<>();
        Iterators.tryIterate(manager.getGroupList(), group -> {
            String groupName = MigrationUtils.standardizeName(group.getName());
            int groupWeight = maxWeight - group.getRank();

            Group lpGroup = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

            MigrationUtils.setGroupWeight(lpGroup, groupWeight);

            // migrate data
            migrateEntity(group, lpGroup, groupWeight);

            // remember known ladders
            if (group.isRanked()) {
                ladders.add(group.getRankLadder().toLowerCase());
            }

            this.luckPerms.getGroupManager().saveGroup(lpGroup).join();
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + groupCount.get() + " groups");

        // Migrate all ladders/tracks.
        log(sender, "Starting tracks migration.");
        for (String rankLadder : ladders) {
            Track track = this.luckPerms.getTrackManager().createAndLoadTrack(rankLadder).join();

            // Get a list of all groups in a ladder
            List<Group> ladder = manager.getRankLadder(rankLadder).entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Integer, PermissionGroup>>comparingInt(Map.Entry::getKey).reversed())
                    .map(e -> MigrationUtils.standardizeName(e.getValue().getName()))
                    .map(g -> this.luckPerms.getGroupManager().getGroup(g))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            track.clearGroups();
            ladder.forEach(track::appendGroup);

            this.luckPerms.getTrackManager().saveTrack(track);
        }
        log(sender, "Migrated " + ladders.size() + " tracks");

        // Migrate all users
        log(sender, "Starting user migration.");
        AtomicInteger userCount = new AtomicInteger(0);

        // Increment the max weight from the group migrations. All user meta should override.
        int userWeight = maxWeight + 5;

        Collection<String> userIdentifiers = manager.getBackend().getUserIdentifiers();
        Iterators.tryIterate(userIdentifiers, id -> {
            PermissionUser user = new PermissionUser(id, manager.getBackend().getUserData(id), manager);
            if (isUserEmpty(user)) {
                return;
            }

            UUID u = lookupUuid(id);
            if (u == null) {
                return;
            }

            // load in a user instance
            User lpUser = this.luckPerms.getUserManager().loadUser(u, user.getName()).join();

            // migrate data
            migrateEntity(user, lpUser, userWeight);

            this.luckPerms.getUserManager().cleanupUser(lpUser);
            this.luckPerms.getUserManager().saveUser(lpUser);

            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        });

        // re-enable events
        try {
            enablePexEvents(manager);
        } catch (ReflectiveOperationException e) {
            e.printStackTrace();
        }

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the PermissionsEx jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
        return true;
    }

    private static final Method GET_DATA_METHOD;
    private static final Field TIMED_PERMISSIONS_FIELD;
    private static final Field TIMED_PERMISSIONS_TIME_FIELD;
    private static final Field NATIVE_INTERFACE_FIELD;
    static {
        try {
            GET_DATA_METHOD = PermissionEntity.class.getDeclaredMethod("getData");
            GET_DATA_METHOD.setAccessible(true);

            TIMED_PERMISSIONS_FIELD = PermissionEntity.class.getDeclaredField("timedPermissions");
            TIMED_PERMISSIONS_FIELD.setAccessible(true);

            TIMED_PERMISSIONS_TIME_FIELD = PermissionEntity.class.getDeclaredField("timedPermissionsTime");
            TIMED_PERMISSIONS_TIME_FIELD.setAccessible(true);

            NATIVE_INTERFACE_FIELD = PermissionManager.class.getDeclaredField("nativeI");
            NATIVE_INTERFACE_FIELD.setAccessible(true);
        } catch (NoSuchMethodException | NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Map<String, List<String>> getPermanentPermissions(PermissionEntity entity) {
        try {
            PermissionsData data = (PermissionsData) GET_DATA_METHOD.invoke(entity);
            return data.getPermissionsMap();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean isUserEmpty(PermissionUser user) {
        for (List<String> permissions : user.getAllPermissions().values()) {
            if (!permissions.isEmpty()) {
                return false;
            }
        }

        for (List<PermissionGroup> parents : user.getAllParents().values()) {
            if (!parents.isEmpty()) {
                return false;
            }
        }

        for (Map<String, String> options : user.getAllOptions().values()) {
            if (!options.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    private static void migrateEntity(PermissionEntity entity, PermissionHolder holder, int weight) {
        // migrate permanent permissions
        for (Map.Entry<String, List<String>> worldData : getPermanentPermissions(entity).entrySet()) {
            String world = standardizeWorld(worldData.getKey());
            for (String node : worldData.getValue()) {
                if (node.isEmpty()) continue;
                holder.data().add(MigrationUtils.parseNode(node, true).withContext(DefaultContextKeys.WORLD_KEY, world).build());
            }
        }

        // migrate temporary permissions
        Map<String, List<String>> timedPermissions;
        Map<String, Long> timedPermissionsTime;

        try {
            //noinspection unchecked
            timedPermissions = (Map<String, List<String>>) TIMED_PERMISSIONS_FIELD.get(entity);
            //noinspection unchecked
            timedPermissionsTime = (Map<String, Long>) TIMED_PERMISSIONS_TIME_FIELD.get(entity);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }

        for (Map.Entry<String, List<String>> worldData : timedPermissions.entrySet()) {
            String world = standardizeWorld(worldData.getKey());
            for (String node : worldData.getValue()) {
                if (node.isEmpty()) continue;
                long expiry = timedPermissionsTime.getOrDefault(Strings.nullToEmpty(world) + ":" + node, 0L);
                Node n = MigrationUtils.parseNode(node, true).withContext(DefaultContextKeys.WORLD_KEY, world).expiry(expiry).build();
                if (!n.hasExpired()) {
                    holder.data().add(n);
                }
            }
        }

        // migrate parents
        for (Map.Entry<String, List<PermissionGroup>> worldData : entity.getAllParents().entrySet()) {
            String world = standardizeWorld(worldData.getKey());

            // keep track of primary group
            String primary = null;
            int primaryWeight = Integer.MAX_VALUE;

            for (PermissionGroup parent : worldData.getValue()) {
                String parentName = parent.getName();
                long expiry = 0L;

                // check for temporary parent
                if (entity instanceof PermissionUser) {
                    String expiryOption = entity.getOption("group-" + parentName + "-until", world);
                    if (expiryOption != null) {
                        try {
                            expiry = Long.parseLong(expiryOption);
                        } catch (NumberFormatException e) {
                            // ignore
                        }
                    }
                }

                InheritanceNode n = InheritanceNode.builder(MigrationUtils.standardizeName(parentName)).withContext(DefaultContextKeys.WORLD_KEY, world).expiry(expiry).build();
                if (n.hasExpired()) {
                    continue;
                }

                holder.data().add(n);

                // migrate primary groups
                if (world.equals("global") && holder instanceof User && expiry == 0) {
                    if (parent.getRank() < primaryWeight) {
                        primary = parent.getName();
                        primaryWeight = parent.getRank();
                    }
                }
            }

            if (primary != null && !primary.isEmpty() && !primary.equalsIgnoreCase("default")) {
                User user = ((User) holder);
                user.setPrimaryGroup(primary);
                user.data().remove(InheritanceNode.builder("default").build());
            }
        }

        // migrate prefix / suffix
        String prefix = entity.getOwnPrefix();
        String suffix = entity.getOwnSuffix();

        if (prefix != null && !prefix.isEmpty()) {
            holder.data().add(PrefixNode.builder(prefix, weight).build());
        }

        if (suffix != null && !suffix.isEmpty()) {
            holder.data().add(SuffixNode.builder(suffix, weight).build());
        }

        // migrate options
        for (Map.Entry<String, Map<String, String>> worldData : entity.getAllOptions().entrySet()) {
            String world = standardizeWorld(worldData.getKey());
            for (Map.Entry<String, String> opt : worldData.getValue().entrySet()) {
                if (opt.getKey() == null || opt.getKey().isEmpty() || opt.getValue() == null || opt.getValue().isEmpty()) {
                    continue;
                }

                String key = opt.getKey().toLowerCase();
                boolean ignore = key.equals("prefix") ||
                        key.equals("suffix") ||
                        key.equals("weight") ||
                        key.equals("rank") ||
                        key.equals("rank-ladder") ||
                        key.equals("name") ||
                        key.equals("username") ||
                        (key.startsWith("group-") && key.endsWith("-until"));

                if (ignore) {
                    continue;
                }

                holder.data().add(MetaNode.builder(opt.getKey(), opt.getValue()).withContext(DefaultContextKeys.WORLD_KEY, world).build());
            }
        }
    }

    private static String standardizeWorld(String world) {
        if (world == null || world.isEmpty() || world.equals("*")) {
            world = "global";
        }
        return world.toLowerCase();
    }

    public UUID lookupUuid(String s) {
        UUID uuid = Uuids.parse(s);
        if (uuid == null) {
            try {
                //noinspection deprecation
                uuid = getServer().getOfflinePlayer(s).getUniqueId();
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        if (uuid == null) {
            getLogger().warning("Unable to get a UUID for user identifier: " + s);
        }
        return uuid;
    }

    /*
     * Hack to workaround issue with accessing PEX async.
     * See: https://github.com/lucko/LuckPerms/issues/2102
     */

    private static void disablePexEvents(PermissionManager manager) throws ReflectiveOperationException {
        NativeInterface nativeInterface = (NativeInterface) NATIVE_INTERFACE_FIELD.get(manager);
        NATIVE_INTERFACE_FIELD.set(manager, new DisabledEventsNativeInterface(nativeInterface));
    }

    private static void enablePexEvents(PermissionManager manager) throws ReflectiveOperationException {
        NativeInterface nativeInterface = (NativeInterface) NATIVE_INTERFACE_FIELD.get(manager);
        while (nativeInterface instanceof DisabledEventsNativeInterface) {
            nativeInterface = ((DisabledEventsNativeInterface) nativeInterface).delegate;
            NATIVE_INTERFACE_FIELD.set(manager, nativeInterface);
        }
    }

    private static final class DisabledEventsNativeInterface implements NativeInterface {
        private final NativeInterface delegate;

        private DisabledEventsNativeInterface(NativeInterface delegate) {
            this.delegate = delegate;
        }

        @Override
        public void callEvent(PermissionEvent permissionEvent) {
            // do nothing!
        }

        @Override public String UUIDToName(UUID uuid) { return this.delegate.UUIDToName(uuid); }
        @Override public UUID nameToUUID(String s) { return this.delegate.nameToUUID(s); }
        @Override public boolean isOnline(UUID uuid) { return this.delegate.isOnline(uuid); }
        @Override public UUID getServerUUID() { return this.delegate.getServerUUID(); }
    }


}

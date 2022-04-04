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

import org.bukkit.command.CommandSender;
import org.tyrannyofheaven.bukkit.zPermissions.ZPermissionsService;
import org.tyrannyofheaven.bukkit.zPermissions.dao.PermissionService;
import org.tyrannyofheaven.bukkit.zPermissions.model.EntityMetadata;
import org.tyrannyofheaven.bukkit.zPermissions.model.Entry;
import org.tyrannyofheaven.bukkit.zPermissions.model.Membership;
import org.tyrannyofheaven.bukkit.zPermissions.model.PermissionEntity;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationZPermissions extends MigrationJavaPlugin {
    private LuckPerms luckPerms;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
    }

    @Override
    public void runMigration(CommandSender sender, String[] args) {
        log(sender, "Starting.");

        ZPermissionsService service = getServer().getServicesManager().load(ZPermissionsService.class);
        PermissionService internalService;
        try {
            Field psField = service.getClass().getDeclaredField("permissionService");
            psField.setAccessible(true);
            internalService = (PermissionService) psField.get(service);
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // Migrate all groups
        log(sender, "Starting group migration.");

        Map<UUID, Set<Node>> userParents = new HashMap<>();

        AtomicInteger groupCount = new AtomicInteger(0);
        AtomicInteger maxWeight = new AtomicInteger(0);
        Iterators.tryIterate(internalService.getEntities(true), entity -> {
            String groupName = MigrationUtils.standardizeName(entity.getDisplayName());
            Group group = this.luckPerms.getGroupManager().createAndLoadGroup(groupName).join();

            int weight = entity.getPriority();
            maxWeight.set(Math.max(maxWeight.get(), weight));
            migrateEntity(group, entity, weight);
            MigrationUtils.setGroupWeight(group, weight);

            // store user data for later
            Set<Membership> members = entity.getMemberships();
            for (Membership membership : members) {
                UUID uuid = lookupUuid(membership.getMember());
                if (uuid == null) {
                    continue;
                }

                Set<Node> nodes = userParents.computeIfAbsent(uuid, u -> new HashSet<>());
                if (membership.getExpiration() == null) {
                    nodes.add(InheritanceNode.builder(groupName).build());
                } else {
                    long expiry = membership.getExpiration().toInstant().getEpochSecond();
                    nodes.add(InheritanceNode.builder(groupName).expiry(expiry).build());
                }
            }

            this.luckPerms.getGroupManager().saveGroup(group);
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        });
        log(sender, "Migrated " + groupCount.get() + " groups");

        // Migrate all tracks
        log(sender, "Starting track migration.");
        AtomicInteger trackCount = new AtomicInteger(0);
        Iterators.tryIterate(service.getAllTracks(), t -> {
            String trackName = MigrationUtils.standardizeName(t);
            Track track = this.luckPerms.getTrackManager().createAndLoadTrack(trackName).join();
            track.clearGroups();
            service.getTrackGroups(t).forEach(g -> {
                final Group group = this.luckPerms.getGroupManager().getGroup(g);
                if (group != null) {
                    track.appendGroup(group);
                }
            });
            this.luckPerms.getTrackManager().saveTrack(track);

            log(sender, "Migrated " + trackCount.incrementAndGet() + " tracks so far.");
        });
        log(sender, "Migrated " + trackCount.get() + " tracks");

        // Migrate all users.
        log(sender, "Starting user migration.");
        maxWeight.addAndGet(10);
        AtomicInteger userCount = new AtomicInteger(0);

        Set<UUID> usersToMigrate = new HashSet<>(userParents.keySet());
        usersToMigrate.addAll(service.getAllPlayersUUID());

        Iterators.tryIterate(usersToMigrate, u -> {
            PermissionEntity entity = internalService.getEntity(null, u, false);

            String username = null;
            if (entity != null) {
                username = entity.getDisplayName();
            }

            User user = this.luckPerms.getUserManager().loadUser(u, username).join();

            // migrate permissions & meta
            if (entity != null) {
                migrateEntity(user, entity, maxWeight.get());
            }

            // migrate groups
            Set<Node> parents = userParents.get(u);
            if (parents != null) {
                parents.forEach(node -> user.data().add(node));
            }

            user.setPrimaryGroup(MigrationUtils.standardizeName(service.getPlayerPrimaryGroup(u)));

            this.luckPerms.getUserManager().saveUser(user);
            this.luckPerms.getUserManager().cleanupUser(user);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        });

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the zPermissions jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
    }

    private void migrateEntity(PermissionHolder holder, PermissionEntity entity, int weight) {
        for (Entry e : entity.getPermissions()) {
            if (e.getPermission().isEmpty()) continue;

            if (e.getWorld() != null && !e.getWorld().getName().isEmpty()) {
                holder.data().add(Node.builder(e.getPermission()).value(e.isValue()).withContext(DefaultContextKeys.WORLD_KEY, e.getWorld().getName()).build());
            } else {
                holder.data().add(Node.builder(e.getPermission()).value(e.isValue()).build());
            }
        }

        // only migrate inheritances for groups
        if (entity.isGroup()) {
            for (PermissionEntity inheritance : entity.getParents()) {
                if (!inheritance.getDisplayName().equals(((Group) holder).getName())) {
                    holder.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(inheritance.getDisplayName())).build());
                }
            }
        }

        for (EntityMetadata metadata : entity.getMetadata()) {
            String key = metadata.getName().toLowerCase();
            Object value = metadata.getValue();

            if (key.isEmpty() || value == null) continue;

            String valueString = value.toString();
            if (valueString.isEmpty()) continue;

            if (key.equals("prefix")) {
                holder.data().add(PrefixNode.builder(valueString, weight).build());
            } else if (key.equals("suffix")) {
                holder.data().add(SuffixNode.builder(valueString, weight).build());
            } else {
                holder.data().add(MetaNode.builder(key, valueString).build());
            }
        }
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

}

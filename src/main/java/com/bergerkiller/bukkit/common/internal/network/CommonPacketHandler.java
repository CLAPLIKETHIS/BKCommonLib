package com.bergerkiller.bukkit.common.internal.network;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.ListIterator;
import java.util.logging.Level;

import net.minecraft.server.v1_5_R2.*;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.internal.CommonNMS;
import com.bergerkiller.bukkit.common.internal.CommonPlugin;
import com.bergerkiller.bukkit.common.reflection.SafeField;
import com.bergerkiller.bukkit.common.reflection.SafeMethod;
import com.bergerkiller.bukkit.common.reflection.classes.EntityPlayerRef;
import com.bergerkiller.bukkit.common.reflection.classes.PlayerConnectionRef;
import com.bergerkiller.bukkit.common.utils.CommonUtil;

/**
 * Fallback packet handler which uses an injected PlayerConnection replacement
 */
public class CommonPacketHandler extends PacketHandlerHooked {
	/**
	 * Known plugins that malfunction with the default packet handler
	 */
	private static final String[] incompatibilities = {"Spout"};

	@Override
	public String getName() {
		return "a PlayerConnection hook";
	}

	@Override
	public boolean onEnable() {
		for (String incompatibility : incompatibilities) {
			if(CommonUtil.isPluginInDirectory(incompatibility)) {
				// Fail!
				Plugin plugin = CommonUtil.getPlugin(incompatibility);
				if(plugin != null) {
					failPacketListener(plugin.getClass());
				} else {
					failPacketListener(incompatibility);
				}
				
				return false;
			}
		}
		CommonPlayerConnection.bindAll();
		return true;
	}

	@Override
	public boolean onDisable() {
		CommonPlayerConnection.unbindAll();
		return true;
	}

	@Override
	public void onPlayerJoin(Player player) {
		CommonPlayerConnection.bind(player);
	}

	@Override
	public void sendSilentPacket(Player player, Object packet) {
		final Object connection = EntityPlayerRef.playerConnection.get(Conversion.toEntityHandle.convert(player));
		PlayerConnectionRef.sendPacket(connection, new CommonSilentPacket(packet));
	}

	private static void failPacketListener(Class<?> playerConnectionType) {
		Plugin plugin = CommonUtil.getPluginByClass(playerConnectionType);
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Failed to hook up a PlayerConnection to listen for received and sent packets");
		if (plugin == null) {
			CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "This was caused by an unknown source, class: " + playerConnectionType.getName());
		} else {
			CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "This was caused by a plugin conflict, namely " + plugin.getName());
		}
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Install ProtocolLib to restore protocol compatibility");
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Dev-bukkit: http://dev.bukkit.org/server-mods/protocollib/");
	}
	
	private static void failPacketListener(String pluginName) {
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Failed to hook up a PlayerConnection to listen for received and sent packets");
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "This was caused by a plugin conflict, namely " + pluginName);
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Install ProtocolLib to restore protocol compatibility");
		CommonPlugin.LOGGER_NETWORK.log(Level.SEVERE, "Dev-bukkit: http://dev.bukkit.org/server-mods/protocollib/");
	}

	private static class CommonPlayerConnection extends PlayerConnection {
		private static final List<PlayerConnection> serverPlayerConnections = SafeField.get(CommonNMS.getMCServer().ae(), "c");
		private final PlayerConnection previous;
		private final PacketHandlerHooked handler;

		static {
			// Verify that all receiver methods in PlayerConnection are overrided
			for (Method method : PlayerConnection.class.getDeclaredMethods()) {
				if (method.getReturnType() != void.class || method.getParameterTypes().length != 1 
						|| !Modifier.isPublic(method.getModifiers())) {
					continue;
				}
				Class<?> arg = method.getParameterTypes()[0];
				if (!Packet.class.isAssignableFrom(arg) || arg == Packet.class) {
					continue;
				}
				SafeMethod<Void> commonMethod = new SafeMethod<Void>(method);
				if (!commonMethod.isOverridedIn(CommonPlayerConnection.class)) {
					// NOT OVERRIDED!
					StringBuilder msg = new StringBuilder(200);
					msg.append("Receiver handler ").append(method.getName());
					msg.append('(').append(arg.getSimpleName()).append(')');
					msg.append(" is not overrided!");
					CommonPlugin.LOGGER_NETWORK.log(Level.WARNING, msg.toString());
				}
			}
		}

		private CommonPlayerConnection(MinecraftServer minecraftserver, EntityPlayer entityplayer) {
			super(minecraftserver, entityplayer.playerConnection.networkManager, entityplayer);
			previous = entityplayer.playerConnection;
			handler = (PacketHandlerHooked) CommonPlugin.getInstance().getPacketHandler();
			PlayerConnectionRef.TEMPLATE.transfer(previous, this);
		}

		public static void bindAll() {
			for (Player player : CommonUtil.getOnlinePlayers()) {
				bind(player);
			}
		}

		public static void unbindAll() {
			for (Player player : CommonUtil.getOnlinePlayers()) {
				unbind(player);
			}
		}

		private static boolean isReplaceable(Object playerConnection) {
			return playerConnection instanceof CommonPlayerConnection || playerConnection.getClass() == PlayerConnection.class;
		}

		private static void setPlayerConnection(final EntityPlayer ep, final PlayerConnection connection) {
			final boolean hasCommon = CommonPlugin.hasInstance();
			if (isReplaceable(ep.playerConnection)) {
				// Set it
				ep.playerConnection = connection;
				// Perform a little check-up in 10 ticks
				if (CommonPlugin.hasInstance()) {
					new Task(CommonPlugin.getInstance()) {
						@Override
						public void run() {
							if (hasCommon && ep.playerConnection != connection) {
								// Player connection has changed!
								failPacketListener(ep.playerConnection.getClass());
								CommonPlugin.getInstance().onCriticalFailure();
							}
						}
					}.start(10);
				}
			} else if (hasCommon) {
				// Plugin conflict!
				failPacketListener(ep.playerConnection.getClass());
				CommonPlugin.getInstance().onCriticalFailure();
				return;
			}
			registerPlayerConnection(ep, connection, true);
		}

		private static void registerPlayerConnection(final EntityPlayer ep, final PlayerConnection connection, boolean retry) {
			synchronized (serverPlayerConnections) {
				// Replace existing
				ListIterator<PlayerConnection> iter = serverPlayerConnections.listIterator();
				while (iter.hasNext()) {
					if (iter.next().player == ep) {
						iter.set(connection);
						return;
					}
				}
				if (!retry) {
					CommonPlugin.LOGGER.log(Level.SEVERE, "Failed to (un)register PlayerConnection proxy...bad things may happen!");
					return;
				}
				// We failed to remove it in one go...
				// Remove the old one the next tick but then fail
				CommonUtil.nextTick(new Runnable() {
					public void run() {
						registerPlayerConnection(ep, connection, false);
					}
				});
			}
		}

		public static void bind(Player player) {
			final EntityPlayer ep = CommonNMS.getNative(player);
			if (ep.playerConnection instanceof CommonPlayerConnection) {
				return;
			}
			setPlayerConnection(ep, new CommonPlayerConnection(CommonNMS.getMCServer(), ep));
		}

		public static void unbind(Player player) {
			final EntityPlayer ep = CommonNMS.getNative(player);
			final PlayerConnection previous = ep.playerConnection;
			if (previous instanceof CommonPlayerConnection) {
				PlayerConnection replacement = ((CommonPlayerConnection) previous).previous;
				PlayerConnectionRef.TEMPLATE.transfer(previous, replacement);
				setPlayerConnection(ep, replacement);
			}
		}

		@Override
		public void a(Packet0KeepAlive packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet100OpenWindow packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}

		@Override
		public void handleContainerClose(Packet101CloseWindow packet) {
			if (this.canConfirm(packet))
				super.handleContainerClose(packet);
		}

		@Override
		public void a(Packet102WindowClick packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet103SetSlot packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet104WindowItems packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet105CraftProgressBar packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet106Transaction packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet107SetCreativeSlot packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet108ButtonClick packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet10Flying packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet130UpdateSign packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet131ItemData packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet132TileEntityData packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet14BlockDig packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet15Place packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet16BlockItemSwitch packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet17EntityLocationAction packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet18ArmAnimation packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet19EntityAction packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet1Login packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet200Statistic packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet201PlayerInfo packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet202Abilities packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet203TabComplete packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet204LocaleAndViewDistance packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet205ClientCommand packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet20NamedEntitySpawn packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet22Collect packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet23VehicleSpawn packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet24MobSpawn packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet250CustomPayload packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet252KeyResponse packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet253KeyRequest packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet254GetInfo packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet255KickDisconnect packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet25EntityPainting packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet26AddExpOrb packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet28EntityVelocity packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet29DestroyEntity packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet2Handshake packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet30Entity packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet34EntityTeleport packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet35EntityHeadRotation packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet38EntityStatus packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet39AttachEntity packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet3Chat packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet40EntityMetadata packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet41MobEffect packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet42RemoveMobEffect packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet43SetExperience packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet4UpdateTime packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet51MapChunk packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet52MultiBlockChange packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet53BlockChange packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet54PlayNoteBlock packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet55BlockBreakAnimation packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet56MapChunkBulk packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet5EntityEquipment packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet60Explosion packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet61WorldEvent packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet62NamedSoundEffect packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet6SpawnPosition packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet70Bed packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet71Weather packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet7UseEntity packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet8UpdateHealth packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}
		
		@Override
		public void a(Packet9Respawn packet) {
			if(this.canConfirm(packet))
				super.a(packet);
		}

		private boolean canConfirm(Packet packet) {
			return handler.handlePacketReceive(CommonNMS.getPlayer(this.player), packet, false);
		}

		@Override
		public void sendPacket(Packet packet) {
			if (handler.handlePacketSend(CommonNMS.getPlayer(this.player), packet, false)) {
				super.sendPacket(packet);
			}
		}
	}
}
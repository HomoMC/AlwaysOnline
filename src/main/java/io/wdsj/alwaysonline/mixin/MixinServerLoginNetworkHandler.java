package io.wdsj.alwaysonline.mixin;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.exceptions.AuthenticationUnavailableException;
import com.mojang.authlib.yggdrasil.ProfileResult;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.encryption.NetworkEncryptionException;
import net.minecraft.network.encryption.NetworkEncryptionUtils;
import net.minecraft.network.listener.ServerLoginPacketListener;
import net.minecraft.network.listener.TickablePacketListener;
import net.minecraft.network.packet.c2s.login.LoginKeyC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerLoginNetworkHandler;
import net.minecraft.text.Text;
import net.minecraft.util.Uuids;
import net.minecraft.util.logging.UncaughtExceptionLogger;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.PrivateKey;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static io.wdsj.alwaysonline.AlwaysOnline.CONFIG;
import static io.wdsj.alwaysonline.AlwaysOnline.LOGGER;

@Mixin(ServerLoginNetworkHandler.class)
public abstract class MixinServerLoginNetworkHandler implements ServerLoginPacketListener, TickablePacketListener {

	@Shadow private volatile ServerLoginNetworkHandler.State state;
	@Shadow @Final
	MinecraftServer server;
	@Shadow @Final private byte[] nonce;
	@Shadow @Final
	ClientConnection connection;
	@Shadow public abstract void disconnect(Text reason);
	@Final
	@Shadow
	private static AtomicInteger NEXT_AUTHENTICATOR_THREAD_ID;
	@Shadow String profileName;

	@Shadow abstract void startVerify(GameProfile profile);

	@Unique
	@Final
	private Cache<String, ProfileResult> cache = CacheBuilder.newBuilder()
			.expireAfterWrite(CONFIG.cacheTimeMinutes(), TimeUnit.MINUTES)
            .build();

	/**
	 * @author HaHaWTH
	 * @reason Fuck it, we can't find any other way to do this.
	 */
	@Overwrite
	public void onKey(LoginKeyC2SPacket packet) {
		Validate.validState(this.state == ServerLoginNetworkHandler.State.KEY, "Unexpected key packet", new Object[0]);

		final String string;
		try {
			PrivateKey privateKey = this.server.getKeyPair().getPrivate();
			if (!packet.verifySignedNonce(this.nonce, privateKey)) {
				throw new IllegalStateException("Protocol error");
			}

			SecretKey secretKey = packet.decryptSecretKey(privateKey);
			Cipher cipher = NetworkEncryptionUtils.cipherFromKey(2, secretKey);
			Cipher cipher2 = NetworkEncryptionUtils.cipherFromKey(1, secretKey);
			string = (new BigInteger(NetworkEncryptionUtils.computeServerId("", this.server.getKeyPair().getPublic(), secretKey))).toString(16);
			this.state = ServerLoginNetworkHandler.State.AUTHENTICATING;
			this.connection.setupEncryption(cipher, cipher2);
		} catch (NetworkEncryptionException var7) {
			throw new IllegalStateException("Protocol error", var7);
		}

		Thread thread = new Thread("User Authenticator #" + NEXT_AUTHENTICATOR_THREAD_ID.incrementAndGet()) {
			public void run() {
				String stringx = (String)Objects.requireNonNull(profileName, "Player name not initialized");
				try {
					ProfileResult profileResult;
					profileResult = cache.getIfPresent(stringx);
					if (profileResult == null) {
						profileResult = server.getSessionService().hasJoinedServer(stringx, string, this.getClientAddress());
					}
					if (profileResult != null) {
						GameProfile gameProfile = profileResult.profile();
						cache.put(stringx, profileResult);
						LOGGER.info("UUID of player {} is {}", gameProfile.getName(), gameProfile.getId());
						startVerify(gameProfile);
					} else if (server.isSingleplayer()) {
						LOGGER.warn("Failed to verify username but will let them in anyway!");
						startVerify(Uuids.getOfflinePlayerProfile(stringx));
					} else {
						disconnect(Text.translatable("multiplayer.disconnect.unverified_username"));
						LOGGER.error("Username '{}' tried to join with an invalid session", stringx);
					}
				} catch (AuthenticationUnavailableException var4) {
					if (server.isSingleplayer()) {
						LOGGER.warn("Authentication servers are down but will let them in anyway!");
						startVerify(Uuids.getOfflinePlayerProfile(stringx));
					} else {
						disconnect(Text.translatable("multiplayer.disconnect.authservers_down"));
						LOGGER.error("Couldn't verify username because servers are unavailable");
					}
				}

			}

			@Nullable
			private InetAddress getClientAddress() {
				SocketAddress socketAddress = connection.getAddress();
				return server.shouldPreventProxyConnections() && socketAddress instanceof InetSocketAddress ? ((InetSocketAddress)socketAddress).getAddress() : null;
			}
		};
		thread.setUncaughtExceptionHandler(new UncaughtExceptionLogger(LOGGER));
		thread.start();
	}
	
}
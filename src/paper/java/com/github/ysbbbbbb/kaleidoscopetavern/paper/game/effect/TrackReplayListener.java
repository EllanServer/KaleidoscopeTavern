package com.github.ysbbbbbb.kaleidoscopetavern.paper.game.effect;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * 唯一监听 {@link PlayerTrackEntityEvent} 的薄壳，转发给
 * {@link EffectService#onTrackReplay(PlayerTrackEntityEvent)} 的批处理队列。
 *
 * <p>独立成类而不是挂在 EffectService 上，是为了能通过
 * {@code HandlerList.unregisterAll(this)} 整体注销而不影响 EffectService 的
 * 消费/药水/死亡/加载等其他监听器。没有任何粒子缓存或 upside_down 目标时，
 * EffectService 注销本监听器，让 PlayerTrackEntityEvent 回到 Paper 的
 * 零监听器快速路径。</p>
 */
final class TrackReplayListener implements Listener {
    private final EffectService owner;

    TrackReplayListener(EffectService owner) {
        this.owner = owner;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrack(PlayerTrackEntityEvent event) {
        owner.onTrackReplay(event);
    }
}

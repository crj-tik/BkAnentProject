package com.bkanent.agent.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 监控外部技能目录的文件变更，自动触发热加载，无需重启服务。
 *
 * <p>类似于 Claude Code 新增一个 skill.md 文件后立即可用。</p>
 *
 * <h3>工作原理</h3>
 * <ol>
 *   <li>使用 Java {@link WatchService} 监控配置的外部技能目录</li>
 *   <li>检测到 .md 文件的创建/修改/删除事件后，等待一段消抖时间（debounce）</li>
 *   <li>消抖结束后触发 {@link SkillRegistry#reload()} 重新加载全部技能</li>
 * </ol>
 *
 * <h3>配置</h3>
 * <pre>
 * agent.skills.external-dir=/path/to/skills   # 外部技能目录（可选）
 * agent.skills.watch-enabled=true              # 是否启用文件监控（默认 true）
 * </pre>
 *
 * <p>如果不配置 external-dir，则仅从 classpath 加载，不支持热加载。
 * classpath 内的技能文件变更需要重启（因为 JAR 内的文件无法被 WatchService 监控）。</p>
 */
public class SkillFileWatcher {

    private static final Logger log = LoggerFactory.getLogger(SkillFileWatcher.class);

    private final SkillRegistry registry;
    private final ScheduledExecutorService scheduler;
    private final AtomicLong lastChangeTimestamp = new AtomicLong(0);
    private final AtomicBoolean reloadScheduled = new AtomicBoolean(false);

    private volatile WatchService watchService;
    private volatile boolean running;

    @Value("${agent.skills.external-dir:}")
    private String externalDir;

    @Value("${agent.skills.watch-enabled:true}")
    private boolean watchEnabled;

    /**
     * 消抖时间（毫秒）。在检测到最后一个文件变更后等待此时间再执行 reload，
     * 避免批量文件变更时多次重复加载。
     */
    private static final long DEBOUNCE_MS = 2000;

    public SkillFileWatcher(SkillRegistry registry) {
        this.registry = registry;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "skill-file-watcher");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动文件监控。在 Spring 容器初始化完成后自动调用。
     */
    @PostConstruct
    public void start() {
        if (!watchEnabled || externalDir == null || externalDir.isBlank()) {
            log.info("Skill file watcher disabled (no external-dir configured or watch-enabled=false)");
            return;
        }

        Path dir = Path.of(externalDir);
        if (!Files.isDirectory(dir)) {
            log.warn("Skill external directory does not exist: {}", externalDir);
            return;
        }

        try {
            watchService = FileSystems.getDefault().newWatchService();
            registerRecursive(dir);
            running = true;

            Thread watcherThread = new Thread(this::watchLoop, "skill-file-watcher");
            watcherThread.setDaemon(true);
            watcherThread.start();

            log.info("Skill file watcher started, monitoring: {}", externalDir);
        } catch (Exception e) {
            log.error("Failed to start skill file watcher", e);
        }
    }

    /**
     * 停止文件监控。在 Spring 容器销毁时自动调用。
     */
    @PreDestroy
    public void stop() {
        running = false;
        scheduler.shutdownNow();
        if (watchService != null) {
            try {
                watchService.close();
            } catch (Exception e) {
                log.debug("Error closing watch service", e);
            }
        }
        log.info("Skill file watcher stopped");
    }

    private void registerRecursive(Path dir) throws IOException {
        Files.walkFileTree(dir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                d.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void watchLoop() {
        while (running) {
            try {
                WatchKey key = watchService.poll(1, TimeUnit.SECONDS);
                if (key == null) {
                    continue;
                }

                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Path relativePath = (Path) event.context();
                    if (relativePath.toString().endsWith(".md")) {
                        relevant = true;
                        log.debug("Skill file changed: {} ({})",
                                relativePath, event.kind().name());
                    }
                }

                if (relevant) {
                    scheduleDebouncedReload();
                }

                key.reset();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Error in skill file watch loop", e);
            }
        }
    }

    private void scheduleDebouncedReload() {
        lastChangeTimestamp.set(System.currentTimeMillis());
        if (reloadScheduled.compareAndSet(false, true)) {
            scheduler.schedule(this::debouncedReload, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void debouncedReload() {
        reloadScheduled.set(false);
        long elapsed = System.currentTimeMillis() - lastChangeTimestamp.get();
        if (elapsed < DEBOUNCE_MS) {
            // Another change happened — reschedule
            if (reloadScheduled.compareAndSet(false, true)) {
                scheduler.schedule(this::debouncedReload,
                        DEBOUNCE_MS - elapsed, TimeUnit.MILLISECONDS);
            }
            return;
        }

        log.info("Reloading skills due to file changes...");
        try {
            registry.reload();
        } catch (Exception e) {
            log.error("Failed to reload skills after file change", e);
        }
    }

    /**
     * 手动触发一次技能重载（可通过 REST 端点调用）。
     */
    public void reloadNow() {
        log.info("Manual skill reload triggered");
        registry.reload();
    }
}

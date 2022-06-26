package io.seata.server.logging.logback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.hook.DelayingShutdownHook;
import ch.qos.logback.core.pattern.Converter;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.util.Duration;
import io.seata.common.loader.EnhancedServiceLoader;
import io.seata.common.util.CollectionUtils;
import io.seata.server.logging.listener.SystemPropertyLoggerContextListener;
import org.slf4j.impl.StaticLoggerBinder;
import org.springframework.boot.logging.logback.ColorConverter;
import org.springframework.boot.logging.logback.WhitespaceThrowableProxyConverter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.Assert;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.springframework.boot.context.logging.LoggingApplicationListener.REGISTER_SHUTDOWN_HOOK_PROPERTY;

/**
 * The type of LogbackExtendConfigurator,to load all
 * {@link LogbackLoggingExtendAppenderProvider}.
 *
 * @author wlx
 * @date 2022/6/24 9:53 下午
 * @see LogbackLoggingExtendAppenderProvider
 */
public class LogbackExtendConfigurator {

    private final List<LogbackLoggingExtendAppenderProvider> loggingExtendAppenderProviders;

    private final ConfigurableEnvironment environment;

    private static final AtomicBoolean SHUTDOWN_HOOK_REGISTERED = new AtomicBoolean();

    protected LoggerContext loggerContext = (LoggerContext) StaticLoggerBinder.getSingleton().getLoggerFactory();

    private LogbackExtendConfigurator(ConfigurableEnvironment environment) {
        this.environment = environment;
        this.loggingExtendAppenderProviders = EnhancedServiceLoader.loadAll(
                LogbackLoggingExtendAppenderProvider.class, new Class[]{ConfigurableEnvironment.class}
                , new Object[]{environment});
    }

    /**
     * append special logback logging extend configuration to loggerContext.
     */
    public void doLoggingExtendConfiguration() {
        synchronized (loggerContext.getConfigurationLock()) {
            loadDefaultConversionRule();
            loadDefaultLoggerContextListener();
            if (!CollectionUtils.isEmpty(loggingExtendAppenderProviders)) {
                loggingExtendAppenderProviders.forEach(
                        provider -> {
                            if (provider.shouldAppend()) {
                                provider.appendTo();
                                registerShutdownHookIfNecessary();
                            }
                        }
                );
            }
            checkErrorStatus();
        }
    }

    @SuppressWarnings("unchecked")
    void conversionRule(String conversionWord, Class<? extends Converter<?>> converterClass) {
        Assert.hasLength(conversionWord, "Conversion word must not be empty");
        Assert.notNull(converterClass, "Converter class must not be null");
        Map<String, String> registry = (Map<String, String>) this.loggerContext
                .getObject(CoreConstants.PATTERN_RULE_REGISTRY);
        if (registry == null) {
            registry = new HashMap<>();
            this.loggerContext.putObject(CoreConstants.PATTERN_RULE_REGISTRY, registry);
        }
        registry.put(conversionWord, converterClass.getName());
    }

    void loggerContextListener(LoggerContextListener loggerContextListener) {
        Assert.notNull(loggerContextListener, "loggerContextListener must not be null");
        if (!existLoggingLoggerContextListener(loggerContextListener.getClass())) {
            loggerContext.addListener(loggerContextListener);
        }
    }

    private void loadDefaultConversionRule() {
        conversionRule("clr", ColorConverter.class);
        conversionRule("wex", WhitespaceThrowableProxyConverter.class);
        conversionRule("wEx", org.springframework.boot.logging.logback.ExtendedWhitespaceThrowableProxyConverter.class);
        conversionRule("wEx2", ExtendedWhitespaceThrowableProxyConverter.class);
    }

    private void loadDefaultLoggerContextListener() {
        SystemPropertyLoggerContextListener systemPropertyLoggerContextListener = new SystemPropertyLoggerContextListener();
        systemPropertyLoggerContextListener.setContext(loggerContext);
        systemPropertyLoggerContextListener.start();
        loggerContextListener(systemPropertyLoggerContextListener);
    }

    private static class SingletonHolder {
        private static LogbackExtendConfigurator INSTANCE;
    }

    /**
     * Get resource logbackComposedLoggingExtendProvider.
     *
     * @return the resource logbackComposedLoggingExtendProvider
     */
    public static LogbackExtendConfigurator get(ConfigurableEnvironment environment) {
        if (Objects.isNull(SingletonHolder.INSTANCE)) {
            SingletonHolder.INSTANCE = new LogbackExtendConfigurator(environment);
        }
        return SingletonHolder.INSTANCE;
    }

    private void registerShutdownHookIfNecessary() {
        boolean registerShutdownHook = environment.getProperty(REGISTER_SHUTDOWN_HOOK_PROPERTY, Boolean.class, false);
        // if registerShutdownHook is true ,the shutdownHook have been registered by LoggingApplicationListener
        if (!registerShutdownHook && SHUTDOWN_HOOK_REGISTERED.compareAndSet(false, true)) {
            // register delayingShutdownHook ,delaying 5s to wait other shutdownHook do logging
            DelayingShutdownHook delayingShutdownHook = new DelayingShutdownHook();
            delayingShutdownHook.setContext(loggerContext);
            delayingShutdownHook.setDelay(Duration.valueOf("5000"));
            Runtime.getRuntime().addShutdownHook(new Thread(delayingShutdownHook));
        }
    }


    private boolean existLoggingLoggerContextListener(Class<? extends LoggerContextListener>
                                                              loggerContextListenerClass) {
        List<LoggerContextListener> listenerList = loggerContext.getCopyOfListenerList();
        return listenerList.stream().anyMatch(
                loggerContextListener -> loggerContextListenerClass.equals(loggerContextListener.getClass())
        );
    }

    private void checkErrorStatus() {
        List<Status> statuses = loggerContext.getStatusManager().getCopyOfStatusList();
        StringBuilder errors = new StringBuilder();
        for (Status status : statuses) {
            if (status.getLevel() == Status.ERROR) {
                errors.append((errors.length() > 0) ? String.format("%n") : "");
                errors.append(status.toString());
            }
        }
        if (errors.length() > 0) {
            throw new IllegalStateException(String.format("Logback configuration error detected: %n%s", errors));
        }
    }
}

package com.github.h3yow.metricsemanticlayerjava.error;

/**
 * 指标存在，但请求的版本号不存在。
 */
public final class VersionNotFoundException extends DefinitionException {
    private final String metric;
    private final int requestedVersion;

    public VersionNotFoundException(String metric, int requestedVersion) {
        super("指标 '" + metric + "' 不存在版本 " + requestedVersion);
        this.metric = metric;
        this.requestedVersion = requestedVersion;
    }

    public String metric() {
        return metric;
    }

    public int requestedVersion() {
        return requestedVersion;
    }
}

package com.infrawatch.source;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Binds all "infrawatch.file-source.*" properties.
 *
 * Minimal block to activate file ingestion:
 *
 *   infrawatch.file-source.enabled=true
 *   infrawatch.file-source.path=data/locations.csv
 *   infrawatch.file-source.format=csv
 *   infrawatch.file-source.separator=,
 *   infrawatch.file-source.has-header=true
 */
@ConfigMapping(prefix = "infrawatch.file-source")
public interface FileDataSourceConfig {

    /** Whether file-based ingestion is active. Default: false. */
    @WithDefault("false")
    boolean enabled();

    /**
     * Path to the data file. Resolution order:
     *  1. Absolute path  (/opt/infrawatch/data/locs.csv)
     *  2. Relative to working directory
     *  3. Classpath resource when prefixed with "classpath:"
     */
    @WithDefault("data/locations.csv")
    String path();

    /**
     * File format: csv | tsv | json | custom
     *  csv    – comma-separated; honours has-header + separator
     *  tsv    – tab-separated;   separator auto-overridden to \t
     *  json   – JSON array of location objects; separator unused
     *  custom – arbitrary delimiter set by separator
     */
    @WithDefault("csv")
    String format();

    /**
     * Field separator for csv / tsv / custom.
     * Examples:  ,   |   ;   \t   ::
     */
    @WithDefault(",")
    String separator();

    /** Skip the first row (csv / tsv / custom). Default: true. */
    @WithName("has-header")
    @WithDefault("true")
    boolean hasHeader();

    /** Java Charset name. Default: UTF-8. */
    @WithDefault("UTF-8")
    String encoding();

    /**
     * true  – file used only when ALL API sources have failed (graceful fallback).
     * false – file always used regardless of API availability (demo / air-gap mode).
     */
    @WithName("fallback-only")
    @WithDefault("true")
    boolean fallbackOnly();

    /** Display label for the UI data-source badge. Defaults to filename. */
    Optional<String> label();
}
package org.schabi.newpipe.extractor.services.missav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Multi-domain failover for MissAV main domains.
 *
 * Keeps the currently active domain stable until it is marked as failed
 * (connection refused / reset / timeout). Once the active domain fails, the
 * next healthy domain from the list is picked. When every domain has failed,
 * the failure state is cleared and the default domain is used again, so a
 * temporarily unreachable network cannot exhaust the list permanently.
 *
 * The client app can register user-defined backup domains via
 * {@link #configure(List)}; failure state is in-memory only.
 */
public final class MissAvDomainManager {

    /** Default domain, kept as a constant for backward compatibility. */
    public static final String DEFAULT_DOMAIN = "missav.one";

    private static final List<String> DEFAULT_DOMAINS = Collections.unmodifiableList(
            Arrays.asList("missav.one", "missav.ai", "missav.ws"));

    private static volatile List<String> domains = DEFAULT_DOMAINS;
    private static volatile String currentDomain = DEFAULT_DOMAIN;
    private static final Set<String> failedDomains =
            Collections.synchronizedSet(new LinkedHashSet<String>());

    private MissAvDomainManager() {
    }

    /**
     * Merges user-defined backup domains into the failover list.
     * Call whenever the user edits the custom domain list.
     */
    public static synchronized void configure(final List<String> customDomains) {
        final LinkedHashSet<String> merged = new LinkedHashSet<>(DEFAULT_DOMAINS);
        if (customDomains != null) {
            for (final String domain : customDomains) {
                final String normalized = normalize(domain);
                if (normalized != null) {
                    merged.add(normalized);
                }
            }
        }
        domains = new ArrayList<>(merged);
        if (!domains.contains(currentDomain)) {
            final String next = pickHealthy();
            currentDomain = next != null ? next : DEFAULT_DOMAIN;
        }
    }

    /** Returns the currently active domain (never null). */
    public static String currentDomain() {
        return currentDomain;
    }

    /** Returns {@code https://<current-domain>}. */
    public static String currentBaseUrl() {
        return "https://" + currentDomain();
    }

    /**
     * Marks a domain as failed. If it is the active domain, failover to the
     * next healthy domain happens immediately.
     */
    public static void markFailed(final String domainOrUrl) {
        final String normalized = normalize(domainOrUrl);
        if (normalized == null) {
            return;
        }
        failedDomains.add(normalized);
        if (!normalized.equals(currentDomain())) {
            return;
        }
        final String next = pickHealthy();
        if (next != null && !next.equals(currentDomain())) {
            currentDomain = next;
        }
    }

    /** Clears all failure marks (e.g. when the user changes settings). */
    public static void resetFailures() {
        failedDomains.clear();
    }

    /** Returns the current failover list (defaults first, custom domains appended). */
    public static List<String> getDomains() {
        return Collections.unmodifiableList(new ArrayList<>(domains));
    }

    /** True if the host (or URL) belongs to a known MissAV main domain. */
    public static boolean isKnownMissAvDomain(final String domainOrUrl) {
        final String normalized = normalize(domainOrUrl);
        return normalized != null && domains.contains(normalized);
    }

    /**
     * Picks the first domain that has not failed; clears all failure marks
     * when every domain has failed so the list is never exhausted.
     */
    private static String pickHealthy() {
        for (final String domain : domains) {
            if (!failedDomains.contains(domain)) {
                return domain;
            }
        }
        failedDomains.clear();
        return domains.isEmpty() ? null : domains.get(0);
    }

    /** Normalizes a domain or URL to a bare lowercase host (scheme/www/port stripped). */
    private static String normalize(final String domainOrUrl) {
        if (domainOrUrl == null) {
            return null;
        }
        String value = domainOrUrl.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return null;
        }
        final int schemeEnd = value.indexOf("://");
        if (schemeEnd >= 0) {
            value = value.substring(schemeEnd + 3);
        }
        final int pathStart = value.indexOf('/');
        if (pathStart >= 0) {
            value = value.substring(0, pathStart);
        }
        if (value.startsWith("www.")) {
            value = value.substring(4);
        }
        final int portStart = value.indexOf(':');
        if (portStart >= 0) {
            value = value.substring(0, portStart);
        }
        return value.isEmpty() ? null : value;
    }
}

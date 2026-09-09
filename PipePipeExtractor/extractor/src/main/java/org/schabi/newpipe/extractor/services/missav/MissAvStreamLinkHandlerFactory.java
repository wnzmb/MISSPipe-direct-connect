package org.schabi.newpipe.extractor.services.missav;

import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.LinkHandlerFactory;

public final class MissAvStreamLinkHandlerFactory extends LinkHandlerFactory {
    private static final MissAvStreamLinkHandlerFactory INSTANCE =
            new MissAvStreamLinkHandlerFactory();

    public static MissAvStreamLinkHandlerFactory getInstance() {
        return INSTANCE;
    }

    private MissAvStreamLinkHandlerFactory() {
    }

    @Override
    public String getId(final String url) throws ParsingException {
        return MissAvParsingHelper.extractId(url);
    }

    @Override
    public String getUrl(final String id) {
        return MissAvParsingHelper.toVideoUrl(id);
    }

    @Override
    public boolean onAcceptUrl(final String url) {
        // Accept video URLs on any known MissAV main domain (current, backup or
        // user-configured), but not the bare domain without a video id path.
        if (url == null) {
            return false;
        }
        for (final String domain : MissAvDomainManager.getDomains()) {
            if (url.contains(domain + "/")
                    && !url.endsWith(domain)
                    && !url.endsWith(domain + "/")) {
                return true;
            }
        }
        return false;
    }
}

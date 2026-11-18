(config) => {
    const media = new Map(), links = new Map();
    const path = location.pathname;
    const search = config.search && (
        config.site === 'google' && (path === '/imgres' || /[?&](?:tbm=isch|udm=2)(?:&|$)/.test(location.search)) ||
        config.site === 'bing' && path.startsWith('/images') ||
        config.site === 'yandex' && path.includes('/images'));
    const knownSite = config.site !== 'generic' && !config.search;
    const asUrl = (value, base = document.baseURI) => {
        if (typeof value !== 'string' || !value.trim()) return null;
        try {
            const url = new URL(value, base);
            if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password) return null;
            url.hash = '';
            return url;
        } catch { return null; }
    };
    const kind = url => /\.(jpe?g|png|gif|webp|avif|svg|bmp|tiff?|ico|heic|jxl)$/i.test(url.pathname) ? 'image'
        : /\.(mp4|webm|mov|m4v|ogv|mkv)$/i.test(url.pathname) ? 'video'
        : /\.(pdf|zip|rar|7z|gz|tgz|tar|mp3|flac|wav|ogg|m4a|aac|epub|csv|txt|docx?|xlsx?|pptx?|exe|msi)$/i.test(url.pathname) ? 'file' : '';
    const canonical = asUrl(document.querySelector('link[rel=canonical]')?.href) || asUrl(location.href);
    const permalink = url => url && url.origin === location.origin && (config.site === 'mastodon'
        ? /^\/@[^/]+\/\d+\/?$/.test(url.pathname)
        : config.permalinks.split('|').some(pattern => pattern && (url.pathname + url.search).includes(pattern)));
    const postInfo = node => {
        const scope = config.post ? node?.closest?.(config.post) : null;
        const anchors = scope ? [...scope.querySelectorAll('a[href]')] : [];
        const ownLink = anchors.map(a => asUrl(a.href)).find(permalink);
        const post = ownLink || (permalink(canonical) ? canonical : asUrl(location.href));
        if (config.site === 'youtube' && post.searchParams.has('v')) {
            const id = post.searchParams.get('v');
            post.search = '';
            post.searchParams.set('v', id);
        } else if (knownSite && (ownLink || permalink(canonical))) {
            post.search = '';
        }
        const time = scope?.querySelector('time[datetime]')?.getAttribute('datetime')
            || document.querySelector('meta[property="article:published_time"]')?.content || '';
        const caption = (config.caption && (scope || document).querySelector(config.caption)?.textContent?.trim())
            || (!scope && document.querySelector('meta[property="og:description"]')?.content) || '';
        return {post: post.href, id: (ownLink || permalink(canonical)) ? post.host.replace(/^www\./, '') + post.pathname.replace(/\/$/, '') + post.search : post.href,
            time, caption: caption.slice(0, 16000)};
    };
    const stableAsset = url => {
        const clean = new URL(url);
        for (const key of [...clean.searchParams.keys()]) {
            if (/^(?:token|sig|signature|expires?|expiry|exp|policy|key-pair-id|x-amz-.*|x-goog-.*|s[evptr]|sk(?:oid|tid|t|e|s|v))$/i.test(key)) clean.searchParams.delete(key);
        }
        if (config.site === 'instagram' && /(?:^|\.)(?:cdninstagram\.com|fbcdn\.net)$/.test(clean.hostname)) return clean.pathname.split('/').pop();
        clean.searchParams.sort();
        return clean.href;
    };
    const original = url => {
        if (config.site === 'x' && url.hostname === 'pbs.twimg.com' && url.pathname.startsWith('/media/')) url.searchParams.set('name', 'orig');
        if (config.site === 'pinterest' && /(^|\.)pinimg\.com$/.test(url.hostname)) url.pathname = url.pathname.replace(/^\/\d+x(?:\d+)?\//, '/originals/');
        if (config.site === 'reddit' && /^(?:external-)?preview\.redd\.it$/.test(url.hostname)
            && /^\/[^/]+\.(?:jpe?g|png|gif|webp)$/i.test(url.pathname) && url.hostname === 'preview.redd.it') {
            url.hostname = 'i.redd.it'; url.search = '';
        }
        if (config.site === 'bluesky' && url.hostname === 'cdn.bsky.app') url.pathname = url.pathname.replace('/img/feed_thumbnail/', '/img/feed_fullsize/');
        return url;
    };
    const add = (value, node, hint, info = postInfo(node), priority = 0, assetId) => {
        const raw = asUrl(value);
        if (!raw) return;
        const url = original(raw);
        const type = hint || kind(url);
        if (!type || /\.(?:m3u8|mpd)$/i.test(url.pathname)) return;
        const asset = assetId || stableAsset(url.href);
        const key = info.id + '|' + type + '|' + asset;
        if ((media.get(key)?.priority ?? -1) <= priority) media.set(key, {...info, url: url.href, type, asset, priority});
    };
    const bestSet = value => (value || '').split(/,(?=\s*(?:https?:|\/|[^\s,]+\s+\d))/)
        .map(part => { const [url, size = '1x'] = part.trim().split(/\s+/); return {url, score: parseFloat(size) || 1}; })
        .sort((a, b) => b.score - a.score)[0]?.url;
    const chooseImage = el => {
        const picture = el.closest('picture');
        const visibleSource = picture && [...picture.querySelectorAll('source[srcset]')]
            .find(source => !source.media || matchMedia(source.media).matches);
        return el.getAttribute('data-original') || el.getAttribute('data-full') || el.getAttribute('data-fullsize')
            || el.getAttribute('data-zoom-image') || el.getAttribute('data-hi-res-src')
            || bestSet(visibleSource?.srcset) || bestSet(el.getAttribute('data-srcset')) || bestSet(el.srcset)
            || el.getAttribute('data-src') || el.currentSrc || el.src;
    };
    const addLink = (value, rank = 0) => {
        const url = asUrl(value);
        if (url && !kind(url) && url.href !== location.href) links.set(url.href, Math.max(rank, links.get(url.href) || 0));
    };
    if (search) {
        const result = (url, page) => {
            const source = asUrl(page);
            const post = source?.href || location.href;
            add(url, null, 'image', {post, id: post, caption: '', time: ''}, 10);
        };
        for (const el of document.querySelectorAll('a[href], [m], [data-bem], [data-state], [data-ou]')) {
            const href = asUrl(el.href);
            if (href) {
                result(href.searchParams.get('imgurl'), href.searchParams.get('imgrefurl'));
                if (config.site !== 'google' || href.pathname.startsWith('/search')) {
                    if ([...href.searchParams.keys()].some(key => ['first','start','ijn','p','page'].includes(key))) addLink(href.href, 5);
                }
            }
            if (config.site === 'google') result(el.getAttribute('data-ou'), el.getAttribute('data-ru'));
            for (const key of ['m', 'data-bem', 'data-state']) {
                const value = el.getAttribute(key);
                if (!value) continue;
                try {
                    const meta = JSON.parse(value);
                    const row = meta['serp-item'] || meta;
                    const original = row.img_href || row.imgUrl || row.origUrl || row.murl || row.dups?.[0]?.url;
                    result(original, row.rurl || row.purl || row.pageUrl || row.snippet?.url);
                } catch {}
            }
        }
    } else {
        if (knownSite) {
            const scopes = [...document.querySelectorAll(config.post)];
            if (permalink(canonical)) scopes.push(null);
            for (const scope of scopes) {
                const info = postInfo(scope);
                if (permalink(asUrl(info.post))) add(info.post, scope, 'post', info, 5, 'post');
            }
        }
        const hasScopes = knownSite && document.querySelector(config.post);
        const selector = hasScopes ? config.media : 'img, video, video source';
        for (const el of document.querySelectorAll(selector)) {
            if (hasScopes && !el.closest(config.post)) continue;
            if (knownSite && /avatar|emoji|profile photo/i.test((el.alt || '') + ' ' + (el.className || ''))) continue;
            if (el.tagName === 'IMG') {
                const full = asUrl(el.closest('a[href]')?.href);
                add(full && kind(full) === 'image' ? full.href : chooseImage(el), el, 'image');
            } else add(el.currentSrc || el.src, el, 'video');
        }
        for (const el of document.querySelectorAll('meta[property^="og:image"],meta[property^="og:video"],meta[name="twitter:image"]')) {
            const key = el.getAttribute('property') || el.name;
            if (/^(?:og:(?:image|video)(?::(?:url|secure_url))?|twitter:image)$/.test(key)) {
                const type = key.includes('video') ? 'video' : 'image';
                const url = asUrl(el.content);
                if (type === 'image' || url && kind(url) === 'video') add(el.content, null, type, postInfo(null), 1);
            }
        }
        const inspect = (data, info, depth = 0) => {
            if (!data || typeof data !== 'object' || depth > 30) return;
            if (Array.isArray(data)) {
                for (const node of data) inspect(node, info, depth + 1);
                return;
            }
            const scalar = value => typeof value === 'string' ? value : '';
            const contentUrl = asUrl(data.contentUrl);
            const postUrl = asUrl(data.permalink || data.url || data.mainEntityOfPage?.['@id']);
            const schemaType = scalar(data['@type']);
            const schemaPost = /SocialMediaPosting|DiscussionForumPosting|Article/.test(schemaType);
            const current = {...info};
            if (postUrl && (permalink(postUrl) || schemaPost && postUrl.origin === location.origin)) {
                current.post = postUrl.href;
                current.id = postUrl.host.replace(/^www\./, '') + postUrl.pathname.replace(/\/$/, '') + (config.site === 'youtube' ? postUrl.search : '');
            }
            if (schemaPost || /ImageObject|VideoObject/.test(schemaType) || data.display_url || data.image_versions2) {
                current.caption = scalar(data.caption?.text || data.caption || data.articleBody || data.description) || current.caption;
                current.time = scalar(data.datePublished || data.uploadDate) || (typeof data.taken_at_timestamp === 'number'
                    && data.taken_at_timestamp > 0 && data.taken_at_timestamp < 1e12 ? new Date(data.taken_at_timestamp * 1000).toISOString() : current.time);
            }
            if (contentUrl && schemaType === 'ImageObject') add(contentUrl.href, null, 'image', current, 3);
            if (contentUrl && schemaType === 'VideoObject' && kind(contentUrl) === 'video') add(contentUrl.href, null, 'video', current, 3);
            if (config.site === 'instagram' && (data.display_url || data.image_versions2 || data.video_url || data.video_versions)) {
                const isVideo = data.is_video || data.media_type === 2 || data.video_url || data.video_versions?.length;
                if (isVideo) add(data.video_url || data.video_versions?.[0]?.url, null, 'video', current, 4);
                else add(data.display_url || data.image_versions2?.candidates?.[0]?.url, null, 'image', current, 4);
            }
            if (config.site === 'reddit') {
                if (data.reddit_video) add(data.reddit_video.fallback_url, null, 'video', current, 4);
                if (data.media_metadata && typeof data.media_metadata === 'object') {
                    for (const node of Object.values(data.media_metadata)) add(node?.s?.u?.replaceAll('&amp;', '&'), null, 'image', current, 4);
                }
            }
            if (config.site === 'x' && Array.isArray(data.variants)) {
                const mp4 = data.variants.filter(v => v.content_type === 'video/mp4').sort((a,b) => (b.bitrate || 0) - (a.bitrate || 0));
                add(mp4[0]?.url, null, 'video', current, 4);
            }
            for (const child of Object.values(data)) inspect(child, current, depth + 1);
        };
        const assignedJson = (text, name) => {
            const match = new RegExp('(?:^|[^a-zA-Z0-9_$])' + name + '\\s*=\\s*(?=\\{)').exec(text);
            if (!match) return;
            const start = match.index + match[0].length;
            let depth = 0, quoted = false, escaped = false;
            for (let index = start; index < text.length; index++) {
                const char = text[index];
                if (quoted) {
                    if (escaped) escaped = false;
                    else if (char === String.fromCharCode(92)) escaped = true;
                    else if (char === '"') quoted = false;
                } else if (char === '"') quoted = true;
                else if (char === '{') depth++;
                else if (char === '}' && --depth === 0) return JSON.parse(text.slice(start, index + 1));
            }
        };
        for (const script of document.scripts) {
            const text = script.textContent;
            if (!text || text.length > 8_000_000) continue;
            try {
                if (['application/ld+json', 'application/json'].includes(script.type) || script.id === '__NEXT_DATA__') {
                    inspect(JSON.parse(text), postInfo(null));
                } else if (config.site === 'youtube') {
                    const player = assignedJson(text, 'ytInitialPlayerResponse');
                    if (!player) continue;
                    const info = postInfo(null);
                    info.caption = player.videoDetails?.shortDescription || info.caption;
                    info.time = player.microformat?.playerMicroformatRenderer?.publishDate || info.time;
                    if (permalink(asUrl(info.post))) {
                        add(info.post, null, 'post', info, 7, 'post');
                        const format = (player.streamingData?.formats || [])
                            .filter(format => format.url && format.mimeType?.startsWith('video/') && !format.drmFamilies?.length)
                            .sort((a, b) => (b.height || 0) - (a.height || 0))[0];
                        if (format) add(format.url, null, 'video', info, 7, 'youtube-progressive');
                    }
                } else if (config.site === 'imgur' || config.site === 'x' || config.site === 'reddit') {
                    const state = assignedJson(text, '__INITIAL_STATE__') || assignedJson(text, '__PRELOADED_STATE__');
                    if (state) inspect(state, postInfo(null));
                }
            } catch {}
        }
        for (const anchor of document.querySelectorAll('a[href],link[rel=next][href]')) {
            const url = asUrl(anchor.href);
            if (!url) continue;
            const next = /(^|\s)next(\s|$)/i.test(anchor.rel || '') ||
                /^(?:next(?: page)?|older(?: posts)?|›|»|→)$/i.test((anchor.textContent || '').trim());
            if (next || permalink(url) || !knownSite) addLink(url.href, next ? 10 : permalink(url) ? 5 : 0);
            if (kind(url)) add(url.href, anchor, kind(url));
        }
    }
    for (const rule of config.rules) {
        let selected;
        try { selected = document.querySelectorAll(rule.selector); }
        catch { throw new Error('Invalid extraction selector: ' + rule.selector); }
        for (const el of selected) {
            const value = el.getAttribute(rule.attribute);
            if (rule.type === 'page') addLink(value, 10);
            else add(value, el, rule.type, postInfo(el), 10);
        }
    }
    return {media: [...media.values()], links: [...links].sort((a,b) => b[1] - a[1]).map(([url]) => url)};
}

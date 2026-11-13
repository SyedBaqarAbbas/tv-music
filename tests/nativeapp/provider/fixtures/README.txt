Catalogue parser fixtures, captured 14 November 2026.

public-search.json retains four public responsive-list rows from an unauthenticated
YouTube Music search for "aespa Lemonade". library-shelf.json retains three rows
from the public album result in a playlist-style shelf matching the liked-tracks
browse envelope. The latter validates the shared row/container shape, not access
to a real account or completeness of a private library.

Only metadata required by the parser is retained. Tracking/logging fields, menus,
request context, account data, cookies, headers, tokens, and expiring media URLs
are absent. Tests make no network calls. Titles and artist metadata are retained
to catch actual upstream field layout differences; no lyrics or media are stored.

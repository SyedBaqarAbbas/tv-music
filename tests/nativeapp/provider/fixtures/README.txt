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

artist-albums.json and album-easy.json are sanitized public WEB_REMIX responses for
LE SSERAFIM and the selected EASY release, captured 2026-11-14. The selected
server edition has seven ordered tracks (including sped-up and slowed versions).
It must not be truncated to the five tracks of another edition. Browse params
remain on album cards so the selected edition is requested. Telemetry, menus,
visitor context, and account fields are excluded. Tests add unrelated shelves
and continuations to ensure album queues contain only the primary track list.

album-drama.json is a sanitized public WEB_REMIX response for aespa's
Drama - The 4th Mini Album, captured 2026-11-14. Its six ordered song rows
omit thumbnails; only the selected primary album header provides the cover.
Regression checks require that cover on every missing row, preserve explicit
row artwork, and reject artist-avatar/related-image substitutions. Selected
collection artwork is used only when the primary header cover is absent.

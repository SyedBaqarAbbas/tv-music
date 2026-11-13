import "./compat.js";
import "@braccato/core/element";
import tvTheme from "./theme.css";

// The application supplies the playback clock and seek handler. No timers or network requests here.
export const theme = tvTheme;
export const version = "1.21.5";

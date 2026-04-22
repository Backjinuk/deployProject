import { CSSProperties } from "react";
import { theme } from "./theme";

export const styles: Record<string, CSSProperties> = {
    customCard: {
        padding: "1.15rem",
    },
    controlsRow: {
        display: "flex",
        alignItems: "center",
        gap: "0.9rem",
        flexWrap: "wrap",
        marginBottom: "1.15rem",
    },
    separator: {
        color: theme.fgMuted,
        fontWeight: 700,
    },
    toggles: {
        marginLeft: "auto",
        display: "flex",
        alignItems: "center",
        gap: "0.8rem",
    },
    versionRow: {
        display: "flex",
        alignItems: "center",
        gap: "0.7rem",
        marginBottom: "1.1rem",
        flexWrap: "wrap",
    },
    versionLabel: {
        minWidth: "88px",
        color: theme.fgStrong,
        fontWeight: 700,
        fontSize: "0.92rem",
    },
    versionInput: {
        flex: 1,
        minWidth: "240px",
        borderRadius: "10px",
        border: `1px solid ${theme.borderStrong}`,
        background: theme.bgElevated,
        color: theme.fgStrong,
    },
    dateInput: {
        padding: "0.65rem 0.8rem",
        borderRadius: "10px",
        border: `1px solid ${theme.borderStrong}`,
        background: theme.bgElevated,
        color: theme.fgStrong,
        minWidth: "128px",
        outline: "none",
    },
    extractBtn: {
        width: "100%",
        background: `linear-gradient(135deg, ${theme.brandDeep} 0%, ${theme.brand} 100%)`,
        color: "#fff",
        padding: "0.72rem",
        border: "none",
        borderRadius: "12px",
        fontWeight: 700,
        letterSpacing: "0.01em",
        boxShadow: "0 8px 20px rgba(23, 82, 160, 0.22)",
        transition: "transform 0.15s ease, box-shadow 0.2s ease",
        cursor: "pointer",
    },
};

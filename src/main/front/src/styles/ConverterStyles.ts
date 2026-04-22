import { CSSProperties } from "react";
import { theme } from "./theme";

export const styles: Record<string, CSSProperties> = {
    wrapper: {
        minHeight: "100vh",
        width: "100%",
        padding: "2.8rem 1rem 3.2rem",
    },
    container: {
        maxWidth: "1040px",
        margin: "0 auto",
    },
    converterHeader: {
        display: "flex",
        justifyContent: "space-between",
        alignItems: "center",
        marginBottom: "1.2rem",
        gap: "1rem",
        flexWrap: "wrap",
    },
    converterHeaderTitle: {
        margin: 0,
        fontSize: "2rem",
        letterSpacing: "-0.02em",
        color: theme.fgStrong,
        fontWeight: 800,
    },
    headerActions: {
        display: "flex",
        gap: "0.6rem",
        flexWrap: "wrap",
    },
    customCardSite: {
        background: theme.bgSurface,
        borderRadius: theme.radius,
        border: `1px solid ${theme.borderSoft}`,
        boxShadow: theme.shadowSoft,
        padding: "1.2rem",
        margin: "0 auto 1rem",
        backdropFilter: "blur(6px)",
    },
    customCardPath: {
        background: theme.bgSurface,
        borderRadius: theme.radius,
        border: `1px solid ${theme.borderSoft}`,
        boxShadow: theme.shadowSoft,
        padding: "1.2rem",
        margin: "0 auto",
        backdropFilter: "blur(6px)",
    },
};

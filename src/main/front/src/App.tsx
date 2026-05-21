import React, { useEffect, useState } from "react";
import "./App.css";
import ConverterMain from "./component/path/ConverterMain";
import DownloadPage from "./component/download/DownloadPage";
import { serverApi, uiMode } from "./api/http";

type ThemeMode = "light" | "dark";
type ThemePreferenceResponse = {
    themeMode?: ThemeMode | null;
};

const THEME_STORAGE_KEY = "deployKitTheme";

const getInitialThemeMode = (): ThemeMode => {
    if (typeof window === "undefined") return "light";

    const savedTheme = window.localStorage.getItem(THEME_STORAGE_KEY);
    if (savedTheme === "light" || savedTheme === "dark") return savedTheme;

    return window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
};

const isThemeMode = (value: unknown): value is ThemeMode => value === "light" || value === "dark";

function App() {
    const [themeMode, setThemeMode] = useState<ThemeMode>(getInitialThemeMode);
    const [isThemePreferenceReady, setIsThemePreferenceReady] = useState(uiMode !== "APP");

    useEffect(() => {
        if (uiMode !== "APP") return;

        let cancelled = false;

        serverApi
            .get<ThemePreferenceResponse>("/api/preferences/theme")
            .then((response) => {
                if (cancelled) return;
                if (isThemeMode(response.data.themeMode)) {
                    setThemeMode(response.data.themeMode);
                }
            })
            .catch((error) => {
                console.info("theme preference load failed:", error);
            })
            .finally(() => {
                if (!cancelled) setIsThemePreferenceReady(true);
            });

        return () => {
            cancelled = true;
        };
    }, []);

    useEffect(() => {
        document.documentElement.dataset.theme = themeMode;
        window.localStorage.setItem(THEME_STORAGE_KEY, themeMode);

        if (uiMode !== "APP" || !isThemePreferenceReady) return;

        serverApi
            .put("/api/preferences/theme", { themeMode })
            .catch((error) => console.info("theme preference save failed:", error));
    }, [themeMode, isThemePreferenceReady]);

    const toggleThemeMode = () => {
        setThemeMode((prev) => (prev === "dark" ? "light" : "dark"));
    };

    return (
        <div className="App" data-theme={themeMode}>
            {uiMode === "DOWNLOAD" ? (
                <DownloadPage />
            ) : (
                <ConverterMain themeMode={themeMode} onToggleThemeMode={toggleThemeMode} />
            )}
        </div>
    );
}

export default App;

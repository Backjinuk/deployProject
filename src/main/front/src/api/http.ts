import axios from "axios";

const INSTALLER_DOWNLOAD_PATH = "/download/deploykit.exe";
const LEGACY_INSTALLER_DOWNLOAD_PATH = "/download/deploy-project.exe";
const PRODUCTION_INSTALLER_DOWNLOAD_BASE_URL = "https://deploy.jinuk.dev";
const DEFAULT_LATEST_VERSION_URL = `${PRODUCTION_INSTALLER_DOWNLOAD_BASE_URL}/version.json`;

type DeployProjectRuntimeConfig = {
    uiMode?: string;
    installerDownloadUrl?: string;
    appVersion?: string;
    latestVersionUrl?: string;
};

declare global {
    interface Window {
        __DEPLOY_PROJECT_CONFIG__?: DeployProjectRuntimeConfig;
    }
}

const isBrowser = typeof window !== "undefined";

const isLocalHost = (hostname: string) => hostname === "localhost" || hostname === "127.0.0.1";

const isLocalBrowser = () => {
    if (!isBrowser) return true;
    const hostname = window.location.hostname || "localhost";
    const isDevServerPort = window.location.port === "3000" || window.location.port === "8080";
    return isLocalHost(hostname) || isDevServerPort;
};

const isInstallerDownloadPath = (value: string) => {
    const trimmed = value.trim();
    if (trimmed === INSTALLER_DOWNLOAD_PATH || trimmed === LEGACY_INSTALLER_DOWNLOAD_PATH) return true;

    try {
        const pathname = new URL(trimmed, PRODUCTION_INSTALLER_DOWNLOAD_BASE_URL).pathname;
        return pathname === INSTALLER_DOWNLOAD_PATH || pathname === LEGACY_INSTALLER_DOWNLOAD_PATH;
    } catch {
        return false;
    }
};

const resolveInstallerDownloadUrl = (value?: string) => {
    const trimmed = (value ?? "").trim();
    if (!trimmed) return `${PRODUCTION_INSTALLER_DOWNLOAD_BASE_URL}${INSTALLER_DOWNLOAD_PATH}`;

    if (!isLocalBrowser() && isInstallerDownloadPath(trimmed)) {
        return `${PRODUCTION_INSTALLER_DOWNLOAD_BASE_URL}${INSTALLER_DOWNLOAD_PATH}`;
    }

    return trimmed;
};

const runtimeConfig = typeof window !== "undefined" ? window.__DEPLOY_PROJECT_CONFIG__ : undefined;
const configuredUiMode = (runtimeConfig?.uiMode || process.env.REACT_APP_UI_MODE || "APP").toUpperCase();

export const uiMode = configuredUiMode === "DOWNLOAD" ? "DOWNLOAD" : "APP";
export const installerDownloadUrl =
    resolveInstallerDownloadUrl(runtimeConfig?.installerDownloadUrl || process.env.REACT_APP_INSTALLER_DOWNLOAD_URL);
export const appVersion = runtimeConfig?.appVersion || process.env.REACT_APP_APP_VERSION || "0.0.0";
export const latestVersionUrl =
    runtimeConfig?.latestVersionUrl || process.env.REACT_APP_LATEST_VERSION_URL || DEFAULT_LATEST_VERSION_URL;

export const serverApi = axios.create({
    baseURL: "",
});

export const localApi = axios.create({
    baseURL: "",
});

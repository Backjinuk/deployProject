import axios from "axios";

const DEFAULT_API_PORT = process.env.REACT_APP_API_PORT || "9090";
const LOCAL_API_BASE_URL = `http://localhost:${DEFAULT_API_PORT}`;
const PRODUCTION_API_BASE_URL =
    process.env.REACT_APP_PRODUCTION_API_BASE_URL || `http://backjin.iptime.org:${DEFAULT_API_PORT}`;
const INSTALLER_DOWNLOAD_PATH = "/download/deploy-project.exe";

type ServerApiMode = "LOCAL" | "REMOTE";

type DeployProjectRuntimeConfig = {
    uiMode?: string;
    serverApiMode?: string;
    remoteApiBaseUrl?: string;
    localApiBaseUrl?: string;
    installerDownloadUrl?: string;
};

declare global {
    interface Window {
        __DEPLOY_PROJECT_CONFIG__?: DeployProjectRuntimeConfig;
    }
}

const normalizeBaseUrl = (value?: string) => (value ?? "").trim().replace(/\/+$/, "");
const isBrowser = typeof window !== "undefined";

const isLocalHost = (hostname: string) => hostname === "localhost" || hostname === "127.0.0.1";

const isLocalBrowser = () => {
    if (!isBrowser) return true;
    const hostname = window.location.hostname || "localhost";
    const isDevServerPort = window.location.port === "3000" || window.location.port === "8080";
    return isLocalHost(hostname) || isDevServerPort;
};

const defaultRemoteApiBaseUrl = () => {
    return isLocalBrowser() ? LOCAL_API_BASE_URL : PRODUCTION_API_BASE_URL;
};

const runtimeConfig = typeof window !== "undefined" ? window.__DEPLOY_PROJECT_CONFIG__ : undefined;
const configuredMode = (runtimeConfig?.serverApiMode || process.env.REACT_APP_SERVER_API_MODE || "LOCAL").toUpperCase();
const configuredUiMode = (runtimeConfig?.uiMode || process.env.REACT_APP_UI_MODE || "APP").toUpperCase();

export const uiMode = configuredUiMode === "DOWNLOAD" ? "DOWNLOAD" : "APP";
export const serverApiMode: ServerApiMode = configuredMode === "REMOTE" ? "REMOTE" : "LOCAL";
export const remoteApiBaseUrl = normalizeBaseUrl(
    runtimeConfig?.remoteApiBaseUrl || process.env.REACT_APP_REMOTE_API_BASE_URL || defaultRemoteApiBaseUrl()
);
export const serverApiBaseUrl = serverApiMode === "REMOTE" ? remoteApiBaseUrl : "";
export const localApiBaseUrl = normalizeBaseUrl(
    runtimeConfig?.localApiBaseUrl ||
        process.env.REACT_APP_LOCAL_API_BASE_URL ||
        (uiMode === "APP" ? "" : defaultRemoteApiBaseUrl())
);
export const installerDownloadUrl =
    runtimeConfig?.installerDownloadUrl || process.env.REACT_APP_INSTALLER_DOWNLOAD_URL || `${remoteApiBaseUrl}${INSTALLER_DOWNLOAD_PATH}`;

export const serverApi = axios.create({
    baseURL: serverApiBaseUrl,
});

export const localApi = axios.create({
    baseURL: localApiBaseUrl,
});

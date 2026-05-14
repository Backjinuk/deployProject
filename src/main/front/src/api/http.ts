import axios from "axios";

const DEFAULT_API_PORT = process.env.REACT_APP_API_PORT || "9090";
const INSTALLER_DOWNLOAD_PATH = "/download/deploy-project.exe";

type ServerApiMode = "LOCAL" | "REMOTE";

type DeployProjectRuntimeConfig = {
    uiMode?: string;
    serverApiMode?: string;
    remoteApiBaseUrl?: string;
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

const defaultRemoteApiBaseUrl = () => {
    if (!isBrowser) {
        return `http://localhost:${DEFAULT_API_PORT}`;
    }

    const hostname = window.location.hostname || "localhost";
    const isLocalDev = isLocalHost(hostname);
    const isDevServerPort = window.location.port === "3000" || window.location.port === "8080";

    if (isLocalDev || isDevServerPort) {
        const protocol = window.location.protocol === "https:" ? "https:" : "http:";
        return `${protocol}//${hostname}:${DEFAULT_API_PORT}`;
    }

    // Production HTTPS traffic should go through the reverse proxy on 443.
    // Direct browser access to https://domain:9090 sends TLS bytes to a plain HTTP Tomcat port.
    return window.location.origin;
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
export const installerDownloadUrl =
    runtimeConfig?.installerDownloadUrl || process.env.REACT_APP_INSTALLER_DOWNLOAD_URL || `${remoteApiBaseUrl}${INSTALLER_DOWNLOAD_PATH}`;

export const serverApi = axios.create({
    baseURL: serverApiBaseUrl,
});

export const localApi = axios.create({
    baseURL: "",
});

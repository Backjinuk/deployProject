import axios from "axios";

const DEFAULT_REMOTE_API_BASE_URL = "http://backjin.iptime.org:9090";
//const DEFAULT_REMOTE_API_BASE_URL = "http://localhost:9090";

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

const runtimeConfig = typeof window !== "undefined" ? window.__DEPLOY_PROJECT_CONFIG__ : undefined;
const configuredMode = (runtimeConfig?.serverApiMode || process.env.REACT_APP_SERVER_API_MODE || "LOCAL").toUpperCase();
const configuredUiMode = (runtimeConfig?.uiMode || process.env.REACT_APP_UI_MODE || "APP").toUpperCase();

export const uiMode = configuredUiMode === "DOWNLOAD" ? "DOWNLOAD" : "APP";
export const serverApiMode: ServerApiMode = configuredMode === "REMOTE" ? "REMOTE" : "LOCAL";
export const remoteApiBaseUrl = normalizeBaseUrl(
    runtimeConfig?.remoteApiBaseUrl || process.env.REACT_APP_REMOTE_API_BASE_URL || DEFAULT_REMOTE_API_BASE_URL
);
export const serverApiBaseUrl = serverApiMode === "REMOTE" ? remoteApiBaseUrl : "";
export const installerDownloadUrl =
    runtimeConfig?.installerDownloadUrl || process.env.REACT_APP_INSTALLER_DOWNLOAD_URL || "/download/deploy-project.exe";

export const serverApi = axios.create({
    baseURL: serverApiBaseUrl,
});

export const localApi = axios.create({
    baseURL: "",
});

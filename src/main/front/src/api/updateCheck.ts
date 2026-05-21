import { serverApi } from "./http";

export type UpdateCheckResult = {
    currentVersion: string;
    latestVersion: string;
    installerUrl: string;
    releaseNotes: string[];
    message?: string;
};

export const checkForAppUpdate = async (): Promise<UpdateCheckResult | null> => {
    try {
        const response = await serverApi.get<UpdateCheckResult>("/api/app/update-check", {
            timeout: 5000,
            validateStatus: (status) => status === 200 || status === 204,
        });

        return response.status === 200 ? response.data : null;
    } catch (error) {
        console.info("update check skipped:", error);
        return null;
    }
};

export const openAppUpdateInstaller = async (installerUrl: string): Promise<void> => {
    await serverApi.post(
        "/api/app/open-update-installer",
        { installerUrl },
        { timeout: 5000 }
    );
};

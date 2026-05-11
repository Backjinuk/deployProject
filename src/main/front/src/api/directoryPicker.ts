import axios from "axios";

type DirectoryPickerResponse = {
    path: string | null;
};

export async function selectDirectory(currentPath?: string, title?: string): Promise<string | null> {
    try {
        const response = await axios.post<DirectoryPickerResponse>("/api/select-directory", {
            currentPath: currentPath?.trim() || null,
            title: title?.trim() || null,
        });

        return response.data.path ?? null;
    } catch (error) {
        if (axios.isAxiosError(error)) {
            const responseData = error.response?.data as { message?: string } | undefined;
            const message = responseData?.message || error.message || "Directory picker request failed.";
            throw new Error(message);
        }
        throw error;
    }
}

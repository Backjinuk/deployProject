export interface Site {
    id: number;
    text: string;
    homePath: string;
    localPath: string;
    jdkPath?: string;
    userSeq?: number;
    useYn?: string;
    field?: string;
    value?: string;
}

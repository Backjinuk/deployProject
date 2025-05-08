// src/api/sites.ts
import axios from 'axios';

export interface Site {
    id: number;
    text: string;
    homePath: string;
    javaOld?: string;
    javaNew?: string;
    xmlOld?: string;
    xmlNew?: string;
    jspOld?: string;
    jspNew?: string;
    scriptOld?: string;
    scriptNew?: string;
    localPath: string;
}


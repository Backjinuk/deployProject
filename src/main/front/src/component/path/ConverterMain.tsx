import React, { useCallback, useEffect, useState } from "react";
import axios from "axios";
import "bootstrap/dist/css/bootstrap.min.css";

import { Site } from "../../api/sites";
import SiteSelector from "./StieSelector";
import PathConverter from "./PathConverter";
import NickNameModal from "../login/NickNameModal";
import PathUpdateModal from "../pathModal/PathUpdateModal";
import PathAddModal from "../pathModal/PathAddModal";
import { styles } from "../../styles/ConverterStyles";

type DeployUser = {
    id: number;
    userName: string;
};

const ConverterMain: React.FC = () => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [showNickNameModal, setShowNickNameModal] = useState<boolean>(true);
    const [showPathUpdateModal, setShowPathUpdateModal] = useState<boolean>(false);
    const [showPathAddModal, setShowPathAddModal] = useState<boolean>(false);

    const fetchSites = useCallback((userId: number) => {
        axios
            .post<Site[]>("/api/sites", { id: userId })
            .then((res) => {
                setSites(res.data);
                setSelected((prev) => res.data.find((site) => site.id === prev?.id) ?? null);
            })
            .catch((err) => console.error("사이트 목록 조회 실패:", err));
    }, []);

    const restoreSessionAndSites = useCallback(() => {
        const stored = localStorage.getItem("deployUser");
        if (!stored) {
            setShowNickNameModal(true);
            setSites([]);
            setSelected(null);
            return;
        }

        const deployUser = JSON.parse(stored) as Partial<DeployUser>;
        if (!deployUser.id) {
            setShowNickNameModal(true);
            setSites([]);
            setSelected(null);
            return;
        }

        setShowNickNameModal(false);
        fetchSites(deployUser.id);
    }, [fetchSites]);

    useEffect(() => {
        restoreSessionAndSites();
    }, [restoreSessionAndSites]);

    const handleSaveNick = (nickName: string) => {
        axios
            .post<DeployUser>("/api/login", { userName: nickName })
            .then((res) => {
                localStorage.setItem("deployUser", JSON.stringify(res.data));
                setShowNickNameModal(false);
                fetchSites(res.data.id);
            })
            .catch((err) => console.error("로그인 실패:", err));
    };

    const handleLogout = () => {
        localStorage.removeItem("deployUser");
        setSites([]);
        setSelected(null);
        setShowNickNameModal(true);
    };

    const handlePathUpdateClose = () => {
        setShowPathUpdateModal(false);
        restoreSessionAndSites();
    };

    const handlePathAddClose = () => {
        setShowPathAddModal(false);
        restoreSessionAndSites();
    };

    return (
        <div style={styles.wrapper}>
            <div style={styles.container}>
                <NickNameModal show={showNickNameModal} onSave={handleSaveNick} />
                <PathUpdateModal show={showPathUpdateModal} onPath={handlePathUpdateClose} />
                <PathAddModal show={showPathAddModal} onPathAdd={handlePathAddClose} />

                <div style={styles.converterHeader}>
                    <h1 style={styles.converterHeaderTitle}>배포 패키지 생성기</h1>
                    <div style={styles.headerActions}>
                        <button className="action-btn secondary" onClick={() => setShowPathAddModal(true)}>
                            경로 등록
                        </button>
                        <button className="action-btn secondary" onClick={() => setShowPathUpdateModal(true)}>
                            경로 수정
                        </button>
                        <button className="action-btn danger" onClick={handleLogout}>
                            로그아웃
                        </button>
                    </div>
                </div>

                <div style={styles.customCardSite}>
                    <SiteSelector
                        sites={sites}
                        selectedId={selected?.id ?? null}
                        onSelect={(id) => {
                            const found = sites.find((site) => site.id === id) ?? null;
                            setSelected(found);
                        }}
                    />
                </div>

                {selected && (
                    <div style={styles.customCardPath}>
                        <PathConverter site={selected} />
                    </div>
                )}
            </div>
        </div>
    );
};

export default ConverterMain;

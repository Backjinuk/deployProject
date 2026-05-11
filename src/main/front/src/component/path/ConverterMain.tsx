import React, { useCallback, useEffect, useState } from "react";
import "bootstrap/dist/css/bootstrap.min.css";
import Swal from "sweetalert2";

import { Site } from "../../api/sites";
import { serverApi, serverApiBaseUrl } from "../../api/http";
import SiteSelector from "./StieSelector";
import PathConverter from "./PathConverter";
import NickNameModal from "../login/NickNameModal";
import PathUpdateModal from "../pathModal/PathUpdateModal";
import PathAddModal from "../pathModal/PathAddModal";
import IntroGuideModal from "../guide/IntroGuideModal";
import { styles } from "../../styles/ConverterStyles";

type DeployUser = {
    id: number;
    userName: string;
};

const INTRO_GUIDE_STORAGE_KEY = "deployProjectIntroGuideSeen";

const ConverterMain: React.FC = () => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [showNickNameModal, setShowNickNameModal] = useState<boolean>(true);
    const [showPathUpdateModal, setShowPathUpdateModal] = useState<boolean>(false);
    const [showPathAddModal, setShowPathAddModal] = useState<boolean>(false);
    const [showIntroGuideModal, setShowIntroGuideModal] = useState<boolean>(false);

    const fetchSites = useCallback((userId: number) => {
        serverApi
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

        try {
            const deployUser = JSON.parse(stored) as Partial<DeployUser>;
            if (!deployUser.id) {
                setShowNickNameModal(true);
                setSites([]);
                setSelected(null);
                return;
            }

            setShowNickNameModal(false);
            fetchSites(deployUser.id);
        } catch {
            localStorage.removeItem("deployUser");
            setShowNickNameModal(true);
            setSites([]);
            setSelected(null);
        }
    }, [fetchSites]);

    useEffect(() => {
        restoreSessionAndSites();
    }, [restoreSessionAndSites]);

    useEffect(() => {
        if (showNickNameModal) return;
        if (localStorage.getItem(INTRO_GUIDE_STORAGE_KEY)) return;
        setShowIntroGuideModal(true);
    }, [showNickNameModal]);

    const closeIntroGuide = () => {
        localStorage.setItem(INTRO_GUIDE_STORAGE_KEY, "Y");
        setShowIntroGuideModal(false);
    };

    const handleSaveNick = (nickName: string) => {
        serverApi
            .post<DeployUser>("/api/login", { userName: nickName })
            .then((res) => {
                localStorage.setItem("deployUser", JSON.stringify(res.data));
                setShowNickNameModal(false);
                fetchSites(res.data.id);
            })
            .catch((err) => {
                console.error("로그인 실패:", err);
                const status = err?.response?.status;
                const message = status
                    ? `로그인 요청 실패 (HTTP ${status})`
                    : `백엔드 서버(${serverApiBaseUrl || "현재 서버"}) 또는 DB 연결 상태를 확인해 주세요.`;
                Swal.fire({
                    icon: "error",
                    title: "로그인 실패",
                    text: message,
                }).then();
            });
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
                <IntroGuideModal show={showIntroGuideModal && !showNickNameModal} onClose={closeIntroGuide} />
                <PathUpdateModal show={showPathUpdateModal} onPath={handlePathUpdateClose} />
                <PathAddModal show={showPathAddModal} onPathAdd={handlePathAddClose} />

                <div style={styles.converterHeader}>
                    <h1 style={styles.converterHeaderTitle}>배포 패키지 생성기</h1>
                    <div style={styles.headerActions}>
                        <button className="action-btn secondary" onClick={() => setShowIntroGuideModal(true)}>
                            사용 안내
                        </button>
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

// src/components/converter/ConverterMain.tsx
import React, {useEffect, useState} from "react";
import axios from "axios";
import "bootstrap/dist/css/bootstrap.min.css";

import {Site} from "../../api/sites";
import SiteSelector from "./StieSelector";
import PathConverter from "./PathConverter";
import NickNameModal from "../login/NickNameModal";
import PathModalMain from "../pathModal/PathModalMain";
import PathAddModal from "../pathModal/PathAddModal";

import {styles} from "../../styles/ConverterStyles"; // TS 스타일 임포트

const ConverterMain: React.FC = () => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [nickNameShowModal, setNickNameShowModal] = useState<boolean>(true);
    const [pathModalShow, setPathModalShow] = useState<boolean>(false);
    const [pathAddModalShow, setPathAddModalShow] = useState<boolean>(false);

    useEffect(() => {
        const stored = localStorage.getItem("deployUser");
        if (stored) {
            const deployUser = JSON.parse(stored);
            fetchSites(deployUser);
            setNickNameShowModal(false);
        } else {
            setNickNameShowModal(true);
        }
    }, []);

    const HoverableButton: React.FC<{
        base: React.CSSProperties; hover: React.CSSProperties; children: React.ReactNode; onClick?: () => void;
    }> = ({base, hover, children, onClick}) => {
        const [isHover, setHover] = useState(false);
        return (<button
                onClick={onClick}
                style={isHover ? {...base, ...hover} : base}
                onMouseEnter={() => setHover(true)}
                onMouseLeave={() => setHover(false)}
            >
                {children}
            </button>);
    };

    const fetchSites = (deployUser: any) => {
        axios
            .post<Site[]>("/api/sites", deployUser)
            .then((res) => setSites(res.data))
            .catch((err) => console.error("사이트 목록 조회 실패:", err));
    };

    const handleSaveNick = (nick: string) => {
        axios
            .post("/api/login", {userName: nick})
            .then((res) => {
                localStorage.setItem("deployUser", JSON.stringify(res.data));
                setNickNameShowModal(false);
                fetchSites(res.data);
            })
            .catch((err) => console.error("로그인 실패:", err));
    };

    const handleLogout = () => {
        localStorage.removeItem("deployUser");
        setNickNameShowModal(true);
        setSites([]);
        setSelected(null);
    };

    return (<div style={styles.warraper}>
        <div style={{...styles.container, padding: "2rem 1rem"}}>
            <NickNameModal show={nickNameShowModal} onSave={handleSaveNick}/>
            <PathModalMain show={pathModalShow} onPath={() => setPathModalShow((v) => !v)}/>
            <PathAddModal show={pathAddModalShow} onPathAdd={() => setPathAddModalShow((v) => !v)}/>

            {/* ─── 헤더 ────────────────────────────── */}
            <div style={styles.converterHeader}>
                <h1 style={styles.converterHeaderTitle}>배포 경로 변환기</h1>
                <div style={styles.headerActions}>
                        <HoverableButton base={styles.outlineBtn}
                                         hover={styles.outlineBtnHover}
                                         onClick={() => setPathAddModalShow(true)}
                        >
                            경로 정보 등록
                        </HoverableButton>
                        <HoverableButton base={styles.outlineBtn}
                                         hover={styles.outlineBtnHover}
                                         onClick={() => setPathModalShow(true)}
                        >
                            경로 정보 수정
                        </HoverableButton>
                        <HoverableButton base={styles.logoutBtn}
                                         hover={styles.logoutBtnHover}
                                         onClick={handleLogout}
                        >
                            로그아웃
                        </HoverableButton>
                </div>
            </div>

            {/* ─── 사이트 선택 카드 ──────────────────── */}
            <div style={styles.customCardSite}>
                <SiteSelector
                    sites={sites}
                    selectedId={selected?.id ?? null}
                    onSelect={(id) => {
                        const found = sites.find((s) => s.id === id) || null;
                        setSelected(found);
                    }}
                />
            </div>

            {/* ─── 경로 변환기 카드 ──────────────────── */}
            {selected && (<div style={styles.customCardPath}>
                <PathConverter site={selected}/>
            </div>)}
        </div>
    </div>
)
}

export default ConverterMain;

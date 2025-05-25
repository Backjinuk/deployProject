import {useEffect, useRef, useState} from "react";
import axios from "axios";
import {Site} from "../../api/sites";
import Swal from "sweetalert2"; // 위에서 정의한 인터페이스

export default function PathUpdateModal({show, onPath}: { show: boolean, onPath: () => void }) {
    const [selectedPath, setSelectedPath] = useState<number | string>('');
    const [pathList, setPathList] = useState<Site[]>([]);
    const modalRef = useRef<HTMLDivElement>(null);



    useEffect(() => {
       const handleClickOutside = (event: MouseEvent) => {
           if (modalRef.current && !modalRef.current.contains(event.target as Node)) {
               // 모달 내부 클릭은 무시
               return onPath?.();
           }
       }

        if (show) {
            document.addEventListener("mousedown", handleClickOutside);

            const deployUser = JSON.parse(localStorage.getItem('deployUser') || "{}");
            axios.post<Site[]>("/api/pathList", deployUser)
                .then(res => setPathList(res.data))
                .catch(console.error);
        }

        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };

    }, [show, onPath]);

    if (!show) return null;

    // 모든 필드 업데이트를 위한 제너릭 핸들러
    const handleFieldChange = (id: number | string, field: keyof Site, value: string) => {
        setPathList(prev => prev.map(p => (p.id === id ? {...p, [field]: value} : p)));

        axios.post("/api/updatePath", {"id": id, "field": field, "value": value})
    };


    const deletePath = (id: number) => {
        axios.post("/api/deletePath", {"id": id, "useYn": "N"}).then(() => {
            Swal.fire({
                position: "center", icon: "success", title: "삭제되었습니다.",
            })
        });
    }

    return (<div className="modal d-block" tabIndex={-1} style={{backgroundColor: "rgba(0,0,0,0.5)",}}>
        <div className="modal-dialog modal-dialog-centered" style={{maxWidth: "800px"}} ref={modalRef}>
            <div className="modal-content">

                {/* 헤더 */}
                <div className="modal-header">
                    <div style={{display: "flex", justifyContent: "space-between", width: "100%"}}>
                        <h5 className="modal-title">경로 정보</h5>
                        <div>
                            <button className="btn btn-secondary" onClick={() => {/* 닫기 로직 */
                                onPath();
                            }}>
                                닫기
                            </button>
                        </div>
                    </div>
                </div>

                {/* 바디 */}
                <div className="modal-body">
                    {pathList.map(path => (<div key={path.id} className="mb-3">
                        {/* 아코디언 제목 */}
                        <div
                            style={{
                                cursor: "pointer",
                                fontWeight: "bold",
                                padding: "0.75rem 1rem",
                                backgroundColor: selectedPath === path.id ? "#e6f7ff" : "#ffffff",
                                border: "1px solid #d9d9d9",
                                borderRadius: "4px",
                                margin: 0
                            }}
                            onClick={() => setSelectedPath(selectedPath === path.id ? "" : path.id)}
                        >
                            {path.text /* 사이트 이름만 간단히 보여줘도 됩니다 */}

                            <button
                                className="btn btn-secondary btn-sm float-end"
                                onClick={() => {
                                    setPathList(prev => prev.filter(p => p.id !== path.id));
                                    deletePath(path.id);
                                }}
                            >
                                삭제
                            </button>
                        </div>

                        {/* 슬라이드 애니메이션 */}
                        <div
                            style={{
                                maxHeight: selectedPath === path.id ? "900px" : "0",
                                overflow: "hidden",
                                transition: "max-height 0.3s ease",
                                border: "1px solid #d9d9d9",
                                borderTop: "none",
                                borderRadius: "0 0 4px 4px"
                            }}
                        >
                            <div style={{padding: "0.75rem 1rem", backgroundColor: "#fafafa"}}>
                                {[{key: "text", label: "사이트 이름"},
                                    { key: "homePath", label: "운영 서버 홈 디렉토리" },
                                    {key: "localPath", label: "사용자 로컬 디렉토리"}
                                ].map(({key, label}) => (
                                    <div className="input-group mb-3" key={key}>
                                        <span className="input-group-text">{label}</span>
                                        <div className="form-floating flex-grow-1">
                                            <input
                                                type="text"
                                                className="form-control"
                                                id={`${key}-${path.id}`}
                                                placeholder={label}
                                                value={(path as any)[key] || ""}
                                                onChange={e => handleFieldChange(path.id, key as keyof Site, e.currentTarget.value)}
                                            />
                                            <label htmlFor={`${key}-${path.id}`}>{label}</label>
                                        </div>
                                    </div>))}
                            </div>
                        </div>
                    </div>))}
                </div>

                {/* 푸터 */}
                <div className="modal-footer">
                </div>

            </div>
        </div>
    </div>);
}
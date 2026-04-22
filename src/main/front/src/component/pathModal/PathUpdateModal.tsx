import { useEffect, useRef, useState } from "react";
import axios from "axios";
import Swal from "sweetalert2";
import { Site } from "../../api/sites";

type EditableField = "text" | "homePath" | "localPath";

export default function PathUpdateModal({
    show,
    onPath,
}: {
    show: boolean;
    onPath: () => void;
}) {
    const [selectedPathId, setSelectedPathId] = useState<number | null>(null);
    const [pathList, setPathList] = useState<Site[]>([]);
    const modalRef = useRef<HTMLDivElement>(null);

    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (modalRef.current && !modalRef.current.contains(event.target as Node)) {
                onPath();
            }
        };

        if (show) {
            document.addEventListener("mousedown", handleClickOutside);
            const deployUser = JSON.parse(localStorage.getItem("deployUser") || "{}");
            axios
                .post<Site[]>("/api/pathList", deployUser)
                .then((res) => setPathList(res.data))
                .catch((err) => console.error("path list load failed", err));
        }

        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };
    }, [show, onPath]);

    if (!show) return null;

    const updateFieldLocal = (id: number, field: EditableField, value: string) => {
        setPathList((prev) => prev.map((path) => (path.id === id ? { ...path, [field]: value } : path)));
    };

    const commitFieldUpdate = (id: number, field: EditableField, value: string) => {
        // 수정 이유: 기존 구현은 key 입력마다 API를 호출해 불필요한 요청이 과도하게 발생했다.
        // 로컬 상태는 즉시 반영하고, 서버 반영은 blur 시점에만 수행한다.
        axios.post("/api/updatePath", { id, field, value }).catch((err) => {
            console.error("updatePath failed", err);
        });
    };

    const deletePath = async (id: number) => {
        await axios.post("/api/deletePath", { id, useYn: "N" });
        await Swal.fire({
            position: "center",
            icon: "success",
            title: "경로를 삭제했습니다.",
            timer: 1200,
            showConfirmButton: false,
        });
    };

    return (
        <div className="modal d-block" tabIndex={-1} style={{ backgroundColor: "rgba(0, 0, 0, 0.55)" }}>
            <div className="modal-dialog modal-dialog-centered" style={{ maxWidth: "860px" }} ref={modalRef}>
                <div className="modal-content">
                    <div className="modal-header">
                        <h5 className="modal-title">경로 관리</h5>
                        <button type="button" className="btn-close" aria-label="Close" onClick={onPath} />
                    </div>

                    <div className="modal-body">
                        {pathList.map((path) => (
                            <div key={path.id} className="mb-3">
                                <div
                                    style={{
                                        cursor: "pointer",
                                        fontWeight: 700,
                                        padding: "0.85rem 1rem",
                                        backgroundColor: selectedPathId === path.id ? "#e8f0ff" : "#ffffff",
                                        border: "1px solid #d9e2f0",
                                        borderRadius: "10px",
                                        margin: 0,
                                    }}
                                    onClick={() =>
                                        setSelectedPathId(selectedPathId === path.id ? null : path.id ?? null)
                                    }
                                >
                                    {path.text}
                                    <button
                                        className="btn btn-outline-danger btn-sm float-end"
                                        onClick={async (event) => {
                                            event.stopPropagation();
                                            if (path.id == null) return;
                                            setPathList((prev) => prev.filter((item) => item.id !== path.id));
                                            await deletePath(path.id);
                                        }}
                                    >
                                        삭제
                                    </button>
                                </div>

                                <div
                                    style={{
                                        maxHeight: selectedPathId === path.id ? "900px" : "0",
                                        overflow: "hidden",
                                        transition: "max-height 0.25s ease",
                                        border: "1px solid #d9e2f0",
                                        borderTop: "none",
                                        borderRadius: "0 0 10px 10px",
                                    }}
                                >
                                    <div style={{ padding: "0.9rem 1rem", backgroundColor: "#f8fbff" }}>
                                        {(
                                            [
                                                { key: "text", label: "사이트 이름" },
                                                { key: "homePath", label: "배포 서버 경로" },
                                                { key: "localPath", label: "로컬 프로젝트 경로" },
                                            ] as const
                                        ).map(({ key, label }) => (
                                            <div className="input-group mb-3" key={key}>
                                                <span className="input-group-text">{label}</span>
                                                <div className="form-floating flex-grow-1">
                                                    <input
                                                        type="text"
                                                        className="form-control"
                                                        id={`${key}-${path.id}`}
                                                        placeholder={label}
                                                        value={(path[key] as string) || ""}
                                                        onChange={(e) =>
                                                            path.id != null &&
                                                            updateFieldLocal(path.id, key, e.currentTarget.value)
                                                        }
                                                        onBlur={(e) =>
                                                            path.id != null &&
                                                            commitFieldUpdate(path.id, key, e.currentTarget.value)
                                                        }
                                                    />
                                                    <label htmlFor={`${key}-${path.id}`}>{label}</label>
                                                </div>
                                            </div>
                                        ))}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>

                    <div className="modal-footer">
                        <button className="btn btn-secondary" onClick={onPath}>
                            닫기
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}

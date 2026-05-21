import { useEffect, useRef, useState } from "react";
import Swal from "sweetalert2";
import { serverApi } from "../../api/http";
import { Site } from "../../api/sites";
import { selectDirectory } from "../../api/directoryPicker";

type EditableField = "text" | "homePath" | "localPath" | "jdkPath";

const fieldDefinitions: Array<{ key: EditableField; label: string; canBrowse?: boolean }> = [
    { key: "text", label: "사이트 이름" },
    { key: "homePath", label: "배포 서버 경로" },
    { key: "localPath", label: "로컬 프로젝트 경로", canBrowse: true },
    { key: "jdkPath", label: "JDK 경로", canBrowse: true },
];

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
            serverApi
                .get<Site[]>("/api/pathList")
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
        serverApi.post("/api/updatePath", { id, field, value }).catch((err) => {
            console.error("updatePath failed", err);
        });
    };

    const handleBrowse = async (pathId: number, field: EditableField, currentValue: string, label: string) => {
        try {
            const selectedPath = await selectDirectory(currentValue, label);
            if (!selectedPath) return;
            updateFieldLocal(pathId, field, selectedPath);
            commitFieldUpdate(pathId, field, selectedPath);
        } catch (error) {
            console.error("directory picker failed", error);
            await Swal.fire({
                icon: "error",
                title: "경로 선택 실패",
                text: "탐색기를 열지 못했습니다.",
            });
        }
    };

    const deletePath = async (id: number) => {
        await serverApi.post("/api/deletePath", { id, useYn: "N" });
        await Swal.fire({
            position: "center",
            icon: "success",
            title: "경로를 삭제했습니다.",
            timer: 1200,
            showConfirmButton: false,
        });
    };

    const renderField = (path: Site, key: EditableField, label: string, canBrowse?: boolean) => {
        if (canBrowse && path.id != null) {
            const currentValue = ((path[key] as string) || "").trim();

            return (
                <div className="input-group mb-3 dp-path-field" key={key}>
                    <span className="input-group-text dp-path-label">{label}</span>
                    <input
                        type="text"
                        className="form-control dp-path-input"
                        id={`${key}-${path.id}`}
                        placeholder="선택된 경로가 없습니다."
                        value={currentValue}
                        readOnly
                        onClick={() => handleBrowse(path.id as number, key, currentValue, label)}
                        style={{ cursor: "pointer", backgroundColor: "var(--control-bg)" }}
                    />
                    <button
                        type="button"
                        className="btn btn-outline-secondary dp-path-browse-btn"
                        onClick={() => handleBrowse(path.id as number, key, currentValue, label)}
                    >
                        폴더 선택
                    </button>
                </div>
            );
        }

        return (
            <div className="input-group mb-3 dp-path-field" key={key}>
                <span className="input-group-text dp-path-label">{label}</span>
                <input
                    type="text"
                    className="form-control dp-path-input"
                    id={`${key}-${path.id}`}
                    placeholder={label}
                    value={(path[key] as string) || ""}
                    onChange={(e) =>
                        path.id != null && updateFieldLocal(path.id, key, e.currentTarget.value)
                    }
                    onBlur={(e) =>
                        path.id != null && commitFieldUpdate(path.id, key, e.currentTarget.value)
                    }
                />
            </div>
        );
    };

    return (
        <div className="modal dp-modal-backdrop" tabIndex={-1} style={{ display: "flex", backgroundColor: "rgba(0, 0, 0, 0.55)" }}>
            <div
                className="modal-dialog modal-dialog-centered dp-modal-dialog dp-path-manage-dialog"
                style={{
                    width: "min(1189px, 90vw)",
                    maxWidth: "min(1189px, 90vw)",
                    flexBasis: "min(1189px, 90vw)",
                }}
                ref={modalRef}
            >
                <div className="modal-content dp-modal-content">
                    <div className="modal-header dp-modal-header">
                        <h5 className="modal-title">경로 관리</h5>
                        <button type="button" className="btn-close" aria-label="Close" onClick={onPath} />
                    </div>

                    <div className="modal-body dp-modal-body dp-path-manage-body">
                        {pathList.map((path) => (
                            <div key={path.id} className="mb-3 dp-path-card-shell">
                                <div
                                    className={`dp-path-list-row${selectedPathId === path.id ? " is-active" : ""}`}
                                    onClick={() =>
                                        setSelectedPathId(selectedPathId === path.id ? null : path.id ?? null)
                                    }
                                >
                                    <span className="dp-path-list-title">{path.text}</span>
                                    <button
                                        type="button"
                                        className="dp-path-delete-btn"
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
                                    className={`dp-path-fields-panel${selectedPathId === path.id ? " is-open" : ""}`}
                                >
                                    <div className="dp-path-fields-inner">
                                        {fieldDefinitions.map(({ key, label, canBrowse }) =>
                                            renderField(path, key, label, canBrowse)
                                        )}
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>

                    <div className="modal-footer dp-modal-footer">
                        <button className="btn btn-secondary dp-modal-button dp-modal-button-secondary" onClick={onPath}>
                            닫기
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}

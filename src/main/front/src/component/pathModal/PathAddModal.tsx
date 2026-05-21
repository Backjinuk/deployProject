import { useEffect, useRef, useState } from "react";
import Swal from "sweetalert2";
import { serverApi } from "../../api/http";
import { selectDirectory } from "../../api/directoryPicker";

type PathForm = {
    text: string;
    homePath: string;
    localPath: string;
    jdkPath: string;
};

type PathFieldKey = keyof PathForm;

const fieldDefinitions: Array<{ key: PathFieldKey; label: string; canBrowse?: boolean }> = [
    { key: "text", label: "사이트 이름" },
    { key: "homePath", label: "배포 서버 경로" },
    { key: "localPath", label: "로컬 프로젝트 경로", canBrowse: true },
    { key: "jdkPath", label: "JDK 경로", canBrowse: true },
];

export default function PathAddModal({
    show,
    onPathAdd,
}: {
    show: boolean;
    onPathAdd: () => void;
}) {
    const modalRef = useRef<HTMLDivElement>(null);

    const createInitialForm = (): PathForm => ({
        text: "",
        homePath: "",
        localPath: "",
        jdkPath: "",
    });

    const [form, setForm] = useState<PathForm>(createInitialForm);

    useEffect(() => {
        if (show) {
            setForm(createInitialForm());
        }
    }, [show]);

    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (modalRef.current && !modalRef.current.contains(event.target as Node)) {
                onPathAdd();
            }
        };

        if (show) {
            document.addEventListener("mousedown", handleClickOutside);
        }

        return () => {
            document.removeEventListener("mousedown", handleClickOutside);
        };
    }, [show, onPathAdd]);

    const handleBrowse = async (key: PathFieldKey, label: string) => {
        try {
            const selectedPath = await selectDirectory(form[key], label);
            if (!selectedPath) return;
            setForm((prev) => ({ ...prev, [key]: selectedPath }));
        } catch (error) {
            console.error("directory picker failed", error);
            await Swal.fire({
                icon: "error",
                title: "경로 선택 실패",
                text: "탐색기를 열지 못했습니다.",
            });
        }
    };

    const renderField = (key: PathFieldKey, label: string, canBrowse?: boolean) => {
        if (canBrowse) {
            return (
                <div className="input-group mb-3 dp-path-field" key={key}>
                    <span className="input-group-text dp-path-label">{label}</span>
                    <input
                        type="text"
                        className="form-control dp-path-input"
                        id={key}
                        placeholder="선택된 경로가 없습니다."
                        value={form[key]}
                        readOnly
                        onClick={() => handleBrowse(key, label)}
                        style={{ cursor: "pointer", backgroundColor: "var(--control-bg)" }}
                    />
                    <button
                        type="button"
                        className="btn btn-outline-secondary dp-path-browse-btn"
                        onClick={() => handleBrowse(key, label)}
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
                    id={key}
                    placeholder={label}
                    value={form[key]}
                    onChange={(e) => setForm((prev) => ({ ...prev, [key]: e.target.value }))}
                />
            </div>
        );
    };

    const savePath = async () => {
        if (!form.text.trim() || !form.homePath.trim() || !form.localPath.trim()) {
            await Swal.fire({
                icon: "warning",
                title: "입력 확인",
                text: "필수 항목을 모두 입력해 주세요.",
            });
            return;
        }

        await serverApi.post("/api/savedPath", form);
        await Swal.fire({
            position: "center",
            icon: "success",
            title: "경로를 등록했습니다.",
            timer: 1200,
            showConfirmButton: false,
        });

        setForm(createInitialForm());
        onPathAdd();
    };

    if (!show) return null;

    return (
        <div className="modal dp-modal-backdrop" tabIndex={-1} style={{ display: "flex", backgroundColor: "rgba(0, 0, 0, 0.55)" }}>
            <div
                className="modal-dialog modal-dialog-centered dp-modal-dialog dp-path-add-dialog"
                style={{
                    width: "min(1037px, 90vw)",
                    maxWidth: "min(1037px, 90vw)",
                    flexBasis: "min(1037px, 90vw)",
                }}
                ref={modalRef}
            >
                <div className="modal-content dp-modal-content">
                    <div className="modal-header dp-modal-header">
                        <h5 className="modal-title">경로 등록</h5>
                        <button type="button" className="btn-close" aria-label="Close" onClick={onPathAdd} />
                    </div>

                    <div className="modal-body dp-modal-body">
                        {fieldDefinitions.map(({ key, label, canBrowse }) => renderField(key, label, canBrowse))}
                    </div>

                    <div className="modal-footer dp-modal-footer">
                        <button className="btn btn-outline-secondary dp-modal-button dp-modal-button-secondary" onClick={onPathAdd}>
                            취소
                        </button>
                        <button className="btn btn-primary dp-modal-button dp-modal-button-primary" onClick={savePath}>
                            저장
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}

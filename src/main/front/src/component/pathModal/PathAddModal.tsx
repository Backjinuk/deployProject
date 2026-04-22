import { useEffect, useRef, useState } from "react";
import axios from "axios";
import Swal from "sweetalert2";

type PathForm = {
    text: string;
    homePath: string;
    localPath: string;
    userSeq: number | "";
};

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
        userSeq: JSON.parse(localStorage.getItem("deployUser") || "{}").id || "",
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

    const savePath = async () => {
        if (!form.text.trim() || !form.homePath.trim() || !form.localPath.trim()) {
            await Swal.fire({
                icon: "warning",
                title: "입력 확인",
                text: "모든 항목을 입력해 주세요.",
            });
            return;
        }

        await axios.post("/api/savedPath", form);
        await Swal.fire({
            position: "center",
            icon: "success",
            title: "경로를 등록했습니다.",
            timer: 1200,
            showConfirmButton: false,
        });

        // 수정 이유: 기존 구현은 state 객체를 직접 변경해서 리렌더링이 보장되지 않았다.
        setForm(createInitialForm());
        onPathAdd();
    };

    if (!show) return null;

    return (
        <div className="modal d-block" tabIndex={-1} style={{ backgroundColor: "rgba(0, 0, 0, 0.55)" }}>
            <div className="modal-dialog modal-dialog-centered" style={{ maxWidth: "760px" }} ref={modalRef}>
                <div className="modal-content">
                    <div className="modal-header">
                        <h5 className="modal-title">경로 등록</h5>
                        <button type="button" className="btn-close" aria-label="Close" onClick={onPathAdd} />
                    </div>

                    <div className="modal-body">
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
                                        id={key}
                                        placeholder={label}
                                        value={form[key]}
                                        onChange={(e) => setForm((prev) => ({ ...prev, [key]: e.target.value }))}
                                    />
                                    <label htmlFor={key}>{label}</label>
                                </div>
                            </div>
                        ))}
                    </div>

                    <div className="modal-footer">
                        <button className="btn btn-outline-secondary" onClick={onPathAdd}>
                            취소
                        </button>
                        <button className="btn btn-primary" onClick={savePath}>
                            저장
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
}

import {use, useEffect, useRef, useState} from "react";
import axios from "axios";
import Swal from "sweetalert2";

type  PathInput = {
    text: string;
    homePath: string;
    localPath: string;
};

export default function PathAddModal({show, onPathAdd}: { show: boolean; onPathAdd: () => void; }) {

    // 각 필드에 대한 state를 단일 객체로 관리
    const [form, setForm] = useState({
        text: "",
        homePath: "",
        localPath: "",
        userSeq: JSON.parse(localStorage.getItem('deployUser') || "{}").id || "",
    });
    const modelRef = useRef<HTMLDivElement>(null);

    // 모달 외부 클릭 시 닫기
    useEffect(() => {
        const handleClickOutside = (event: MouseEvent) => {
            if (modelRef.current && !modelRef.current.contains(event.target as Node)){
                onPathAdd?.();
            }
        }

        if (show) {
            document.addEventListener("mousedown", handleClickOutside);
        }

        return () => {
            document.removeEventListener("mousedown", handleClickOutside);}


    }, [show, onPathAdd]);

    const savedPath = () => {
        axios.post('/api/savedPath', form).then( () => {
            Swal.fire({
                position: "center",
                icon: "success",
                title: "저장 완료 되었습니다.",
                showConfirmButton: false,
                timer: 1500
            }).then( () => {
                form.text = "";
                form.homePath = "";
                form.localPath = "";

                // 모달 닫기
                onPathAdd();
            });
        })
    }

    if (!show) return null;

    return (
        <div className="modal d-block" tabIndex={-1} style={{backgroundColor: "rgba(0,0,0,0.5)",}}>

            <div className="modal-dialog modal-dialog-centered" style={{maxWidth: "800px"}} ref={modelRef}>
                <div className="modal-content">

                    {/* 헤더 */}
                    <div className="modal-header">
                        <h5 className="modal-title">경로 정보 등록</h5>
                        <div style={{display: "flex", justifyContent : "right", width : '84%' ,gap: "10px"}}>
                        <button style={{marginLeft : '84%'}} className={"btn btn-primary"} onClick={() => savedPath()}>저장</button>
                        <button type="button" className="btn-close" data-bs-dismiss="modal" aria-label="Close"
                                onClick={() => {
                                    onPathAdd();
                                }}/>

                        </div>
                    </div>

                    {/* 바디 */}
                    <div className="modal-body">
                        <div style={{ padding: "0.75rem 1rem", backgroundColor: "#fafafa" }}>
                            {(
                                [
                                    { key: "text", label: "사이트 이름" },
                                    { key: "homePath", label: "운영 서버 홈 디렉토리" },
                                    { key: "localPath", label: "사용자 로컬 디렉토리" }
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
                                            onChange={e =>
                                                setForm(prev => ({ ...prev, [key]: e.target.value }))
                                            }
                                        />
                                        <label htmlFor={key}>{label}</label>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                </div>
            </div>
        </div>
    )
}
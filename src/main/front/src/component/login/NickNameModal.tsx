import React, { useState } from "react";

interface Props {
    show: boolean;
    onSave: (nickname: string) => void;
}

const NickNameModal: React.FC<Props> = ({ show, onSave }) => {
    const [nick, setNick] = useState("");

    const handleSave = () => {
        const name = nick.trim();
        if (name) onSave(name);
    };

    if (!show) return null;

    return (
        <div className="modal d-block" tabIndex={-1} style={{ backgroundColor: "rgba(0, 0, 0, 0.55)" }}>
            <div className="modal-dialog modal-dialog-centered">
                <div className="modal-content nick-modal">
                    <div className="modal-body">
                        <div className="nick-badge">Deploy</div>
                        <h5 className="nick-title">사용자 이름을 입력해 주세요</h5>
                        <p className="nick-subtitle">로그인 후 최근 배포 경로를 불러옵니다.</p>

                        <input
                            type="text"
                            className="form-control nick-input"
                            placeholder="닉네임"
                            value={nick}
                            onChange={(e) => setNick(e.target.value)}
                            onKeyDown={(event) => {
                                if (event.key === "Enter") handleSave();
                            }}
                        />
                    </div>
                    <div className="modal-footer nick-footer">
                        <button type="button" className="nick-save-btn" onClick={handleSave}>
                            시작하기
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default NickNameModal;

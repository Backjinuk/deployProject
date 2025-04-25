import React, { useState } from 'react';

interface Props {
    show: boolean;
    onSave: (nickname: string) => void;
}

const NicknameModal: React.FC<Props> = ({ show, onSave }) => {
    const [nick, setNick] = useState('');

    const handleSave = () => {
        const name = nick.trim();
        if (name) {
            onSave(name);
        }
    };

    if (!show) return null;
    return (
        <div className="modal d-block" tabIndex={-1} style={{ backgroundColor: 'rgba(0,0,0,0.5)' }}>
            <div className="modal-dialog modal-dialog-centered">
                <div className="modal-content">
                    <div className="modal-header">
                        <h5 className="modal-title">닉네임 입력</h5>
                    </div>
                    <div className="modal-body">
                        <input
                            type="text"
                            className="form-control"
                            placeholder="닉네임을 입력하세요"
                            value={nick}
                            onChange={e => setNick(e.target.value)}
                        />
                    </div>
                    <div className="modal-footer">
                        <button type="button" className="btn btn-primary" onClick={handleSave}>
                            저장
                        </button>
                    </div>
                </div>
            </div>
        </div>
    );
};

export default NicknameModal;
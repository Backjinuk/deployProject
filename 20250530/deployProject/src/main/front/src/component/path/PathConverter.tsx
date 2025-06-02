import React, {useState, forwardRef} from 'react';
import axios from 'axios';
import DatePicker from 'react-datepicker';
import {ko} from 'date-fns/locale';
import 'react-datepicker/dist/react-datepicker.css';

import {Site} from '../../api/sites';
import {styles} from '../../styles/PathConverterStyles';
import Swal from "sweetalert2";

interface Props {
    site: Site;
}

// custom input
const CustomInput = forwardRef<HTMLInputElement, {
    value?: string;
    onClick?: () => void;
}>(({value, onClick}, ref) => (
    <input
        ref={ref}
        value={value}
        onClick={onClick}
        readOnly
        style={styles.dateInput}    // 파란색 Outline
    />
));

const PathConverter: React.FC<Props> = ({site}) => {
    const [startDate, setStartDate] = useState<Date | null>(new Date());
    const [endDate, setEndDate] = useState<Date | null>(new Date());
    const [gitEnabled, setGitEnabled] = useState(true);
    const [statusEnabled, setStatusEnabled] = useState(true);

    const extraction = () => {
        let fileStatusType;

        if (gitEnabled && statusEnabled) {
            fileStatusType = 'ALL'
        } else if (gitEnabled) {
            fileStatusType = 'GIT'
        } else if (statusEnabled) {
            fileStatusType = 'STATUS'
        }

        const ua = navigator.userAgent.toLowerCase();
        let targetOs;

        if (ua.indexOf('windows') > -1) {
            targetOs = 'WINDOWS';
        } else if (ua.indexOf('mac') > -1) {
            targetOs = 'MAC';
        } else if (ua.indexOf('linux') > -1) {
            targetOs = 'LINUX';
        } else {
            console.error('지원하지 않는 OS입니다.');
            return;
        }

        // 로딩 시작
        Swal.fire({
            title: '다운로드 중입니다...',
            text: '잠시만 기다려 주세요.',
            allowOutsideClick: false,
            didOpen: () => {
                Swal.showLoading();
            },
        });

        axios.post('/api/git/extraction', {
                siteId: site.id,
                since: startDate?.toISOString(),
                until: endDate?.toISOString(),
                localPath: site.localPath,
                homePath: site.homePath,
                    fileStatusType: fileStatusType,
                targetOs: targetOs,
            }, {responseType: 'blob',}
        ).then(res => {
            const date = new Date().toISOString().replace(/[:.]/g, '-');
            const disposition = res.headers['content-disposition'];
            let filename = `deployCli-${date}.zip`;
            if (disposition) {
                const match = disposition.match(/filename="(.+)"/);
                if (match && match[1]) filename = match[1];
            }

            const blob = new Blob([res.data], {type: res.headers['content-type']});
            const url = URL.createObjectURL(blob);
            const a = document.createElement('a');
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            URL.revokeObjectURL(url);
            a.remove();

            // 성공 알림
            Swal.fire({
                icon: 'success',
                title: '다운로드 완료!',
                text: filename,
                timer: 2000,
                showConfirmButton: false,
            });
        })
            .catch((err) => {
                console.error('추출 실패:', err);
                Swal.fire({
                    icon: 'error',
                    title: '다운로드 실패',
                    text: '문제가 발생했습니다.',
                });
            });
    };

    return (
        <div style={styles.customCard}>
            <div style={styles.controlsRow}>
                <DatePicker
                    locale={ko}
                    selected={startDate}
                    onChange={setStartDate}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput/>}
                    calendarClassName="custom-calendar"
                />

                <span style={styles.separator}>~</span>

                <DatePicker
                    locale={ko}
                    selected={endDate}
                    onChange={setEndDate}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput/>}
                    calendarClassName="custom-calendar"
                />

                <div style={styles.toggles}>
                    <div className="form-check form-switch">
                        <input
                            className="form-check-input"
                            type="checkbox"
                            id="toggleGit"
                            checked={gitEnabled}
                            onChange={() => setGitEnabled(v => !v)}
                        />
                        <label className="form-check-label" htmlFor="toggleGit">
                            Git
                        </label>
                    </div>
                    <div className="form-check form-switch">
                        <input
                            className="form-check-input"
                            type="checkbox"
                            id="toggleStatus"
                            checked={statusEnabled}
                            onChange={() => setStatusEnabled(v => !v)}
                        />
                        <label className="form-check-label" htmlFor="toggleStatus">
                            Status
                        </label>
                    </div>
                </div>
            </div>

            <button
                onClick={extraction}
                style={styles.extractBtn}
            >
                추출하기
            </button>
        </div>
    );
};

export default PathConverter;

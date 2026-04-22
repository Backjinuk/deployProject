import React, { forwardRef, useState } from "react";
import axios from "axios";
import DatePicker from "react-datepicker";
import { ko } from "date-fns/locale";
import "react-datepicker/dist/react-datepicker.css";
import Swal from "sweetalert2";

import { Site } from "../../api/sites";
import { styles } from "../../styles/PathConverterStyles";

interface Props {
    site: Site;
}

const CustomInput = forwardRef<HTMLInputElement, { value?: string; onClick?: () => void }>(
    ({ value, onClick }, ref) => (
        <input ref={ref} value={value} onClick={onClick} readOnly style={styles.dateInput} />
    )
);

const PathConverter: React.FC<Props> = ({ site }) => {
    const [startDate, setStartDate] = useState<Date | null>(new Date());
    const [endDate, setEndDate] = useState<Date | null>(new Date());
    const [sinceVersion, setSinceVersion] = useState("");
    const [untilVersion, setUntilVersion] = useState("");
    const [gitEnabled, setGitEnabled] = useState(true);
    const [statusEnabled, setStatusEnabled] = useState(true);
    const [isExtracting, setIsExtracting] = useState(false);

    const resolveFileStatusType = (): "ALL" | "DIFF" | "STATUS" | null => {
        if (gitEnabled && statusEnabled) return "ALL";
        if (gitEnabled) return "DIFF";
        if (statusEnabled) return "STATUS";
        return null;
    };

    const resolveTargetOs = (): "WINDOWS" | "MAC" | "LINUX" | null => {
        const ua = navigator.userAgent.toLowerCase();
        if (ua.includes("windows")) return "WINDOWS";
        if (ua.includes("mac")) return "MAC";
        if (ua.includes("linux")) return "LINUX";
        return null;
    };

    const validateVersionInputs = async (): Promise<boolean> => {
        const hasSinceVersion = sinceVersion.trim().length > 0;
        const hasUntilVersion = untilVersion.trim().length > 0;

        // 수정 이유: 버전 범위는 시작/종료를 같이 받아야 의도한 구간 필터가 안정적으로 동작한다.
        if (hasSinceVersion !== hasUntilVersion) {
            await Swal.fire({
                icon: "warning",
                title: "버전 범위 확인",
                text: "시작 버전과 종료 버전을 모두 입력해 주세요.",
            });
            return false;
        }
        return true;
    };

    const extraction = async () => {
        const fileStatusType = resolveFileStatusType();
        if (!fileStatusType) {
            await Swal.fire({
                icon: "warning",
                title: "옵션 확인",
                text: "Git 또는 Status 중 하나 이상을 선택하세요.",
            });
            return;
        }

        if (!(await validateVersionInputs())) {
            return;
        }

        const targetOs = resolveTargetOs();
        if (!targetOs) {
            await Swal.fire({
                icon: "error",
                title: "지원되지 않는 OS",
                text: "운영체제를 감지할 수 없습니다.",
            });
            return;
        }

        setIsExtracting(true);
        Swal.fire({
            title: "패키지 생성 중",
            text: "날짜 + 저장소 버전 조건으로 처리하고 있습니다.",
            allowOutsideClick: false,
            didOpen: () => Swal.showLoading(),
        });

        try {
            const response = await axios.post(
                "/api/git/extraction",
                {
                    siteId: site.id,
                    since: startDate?.toISOString(),
                    until: endDate?.toISOString(),
                    sinceVersion: sinceVersion.trim() || null,
                    untilVersion: untilVersion.trim() || null,
                    localPath: site.localPath,
                    homePath: site.homePath,
                    fileStatusType,
                    targetOs,
                },
                { responseType: "blob", timeout: 600000 }
            );

            const now = new Date().toISOString().replace(/[:.]/g, "-");
            const disposition = response.headers["content-disposition"];
            let filename = `deploy-bundle-${now}.zip`;
            if (disposition) {
                const match = disposition.match(/filename="(.+)"/);
                if (match?.[1]) filename = match[1];
            }

            const blob = new Blob([response.data], { type: response.headers["content-type"] });
            const url = URL.createObjectURL(blob);
            const a = document.createElement("a");
            a.href = url;
            a.download = filename;
            document.body.appendChild(a);
            a.click();
            URL.revokeObjectURL(url);
            a.remove();

            await Swal.fire({
                icon: "success",
                title: "다운로드 완료",
                text: filename,
                timer: 1800,
                showConfirmButton: false,
            });
        } catch (err) {
            console.error("extraction error:", err);
            await Swal.fire({
                icon: "error",
                title: "다운로드 실패",
                text: "서버 응답을 확인해 주세요.",
            });
        } finally {
            setIsExtracting(false);
        }
    };

    return (
        <div style={styles.customCard}>
            <div style={styles.controlsRow}>
                <DatePicker
                    locale={ko}
                    selected={startDate}
                    onChange={setStartDate}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput />}
                    calendarClassName="custom-calendar"
                />
                <span style={styles.separator}>~</span>
                <DatePicker
                    locale={ko}
                    selected={endDate}
                    onChange={setEndDate}
                    dateFormat="yyyy-MM-dd"
                    customInput={<CustomInput />}
                    calendarClassName="custom-calendar"
                />

                <div style={styles.toggles}>
                    <div className="form-check form-switch">
                        <input
                            className="form-check-input"
                            type="checkbox"
                            id="toggleGit"
                            checked={gitEnabled}
                            onChange={() => setGitEnabled((v) => !v)}
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
                            onChange={() => setStatusEnabled((v) => !v)}
                        />
                        <label className="form-check-label" htmlFor="toggleStatus">
                            Status
                        </label>
                    </div>
                </div>
            </div>

            <div style={styles.versionRow}>
                <label style={styles.versionLabel}>저장소 버전</label>
                <input
                    className="form-control"
                    style={styles.versionInput}
                    placeholder="시작 버전 (예: a1b2c3d / 1200)"
                    value={sinceVersion}
                    onChange={(e) => setSinceVersion(e.target.value)}
                />
                <span style={styles.separator}>~</span>
                <input
                    className="form-control"
                    style={styles.versionInput}
                    placeholder="종료 버전 (예: f6e5d4c / 1250)"
                    value={untilVersion}
                    onChange={(e) => setUntilVersion(e.target.value)}
                />
            </div>

            <button onClick={extraction} style={styles.extractBtn} disabled={isExtracting}>
                {isExtracting ? "생성 중..." : "패키지 추출"}
            </button>
        </div>
    );
};

export default PathConverter;

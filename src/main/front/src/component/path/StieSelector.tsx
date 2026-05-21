import React, { useEffect, useRef, useState } from "react";
import { Site } from "../../api/sites";

interface Props {
    sites: Site[];
    selectedId: number | null;
    onSelect: (id: number) => void;
}

const SiteSelector: React.FC<Props> = ({ sites, selectedId, onSelect }) => {
    const [isOpen, setIsOpen] = useState(false);
    const selectorRef = useRef<HTMLDivElement>(null);
    const selectedSite = sites.find((site) => site.id === selectedId) ?? null;

    useEffect(() => {
        if (!isOpen) return;

        const handleMouseDown = (event: MouseEvent) => {
            if (!selectorRef.current?.contains(event.target as Node)) {
                setIsOpen(false);
            }
        };

        document.addEventListener("mousedown", handleMouseDown);
        return () => document.removeEventListener("mousedown", handleMouseDown);
    }, [isOpen]);

    return (
        <div className="site-selector" ref={selectorRef}>
            <label id="siteSelectLabel" className="site-selector-label">
                배포 대상 선택
            </label>
            <button
                type="button"
                className="site-select-trigger"
                aria-labelledby="siteSelectLabel"
                aria-expanded={isOpen}
                disabled={sites.length === 0}
                onClick={() => setIsOpen((prev) => !prev)}
            >
                <span>{selectedSite?.text ?? "등록된 경로를 선택해 주세요"}</span>
                <span className="site-select-chevron" aria-hidden="true" />
            </button>

            {isOpen && (
                <div className="site-select-menu" role="listbox" aria-labelledby="siteSelectLabel">
                    {sites.length === 0 && <div className="site-select-empty">등록된 경로가 없습니다.</div>}
                    {sites.map((site) => (
                        <button
                            key={site.id}
                            type="button"
                            className={`site-select-option${site.id === selectedId ? " active" : ""}`}
                            role="option"
                            aria-selected={site.id === selectedId}
                            onClick={() => {
                                onSelect(site.id);
                                setIsOpen(false);
                            }}
                        >
                            <span>{site.text}</span>
                            <small>{site.localPath}</small>
                        </button>
                    ))}
                </div>
            )}
        </div>
    );
};

export default SiteSelector;

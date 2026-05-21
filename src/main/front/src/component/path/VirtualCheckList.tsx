import React, { CSSProperties, ReactNode, useEffect, useMemo, useRef, useState } from "react";

export type VirtualCheckListItem = {
    key: string;
    label: string;
    checked: boolean;
    badge?: string;
    onToggle: () => void;
};

type Props = {
    items: VirtualCheckListItem[];
    listStyle: CSSProperties;
    rowStyle: CSSProperties;
    activeRowStyle: CSSProperties;
    inputStyle: CSSProperties;
    textStyle: CSSProperties;
    badgeStyle?: CSSProperties;
    emptyContent?: ReactNode;
    suspendScroll?: boolean;
    rowHeight?: number;
};

const DEFAULT_ROW_HEIGHT = 38;
const OVERSCAN_COUNT = 8;

const VirtualCheckList: React.FC<Props> = ({
    items,
    listStyle,
    rowStyle,
    activeRowStyle,
    inputStyle,
    textStyle,
    badgeStyle,
    emptyContent,
    suspendScroll = false,
    rowHeight = DEFAULT_ROW_HEIGHT,
}) => {
    const containerRef = useRef<HTMLDivElement>(null);
    const [scrollTop, setScrollTop] = useState(0);
    const [viewportHeight, setViewportHeight] = useState(0);

    useEffect(() => {
        const element = containerRef.current;
        if (!element) return;

        const updateHeight = () => setViewportHeight(element.clientHeight);
        updateHeight();

        if (typeof ResizeObserver === "undefined") {
            window.addEventListener("resize", updateHeight);
            return () => window.removeEventListener("resize", updateHeight);
        }

        const observer = new ResizeObserver(updateHeight);
        observer.observe(element);
        return () => observer.disconnect();
    }, []);

    useEffect(() => {
        setScrollTop(0);
        if (containerRef.current) containerRef.current.scrollTop = 0;
    }, [items]);

    const visibleRange = useMemo(() => {
        if (items.length === 0) return { start: 0, end: 0 };

        const start = Math.max(0, Math.floor(scrollTop / rowHeight) - OVERSCAN_COUNT);
        const visibleCount = Math.ceil((viewportHeight || rowHeight * 8) / rowHeight) + OVERSCAN_COUNT * 2;
        const end = Math.min(items.length, start + visibleCount);

        return { start, end };
    }, [items.length, rowHeight, scrollTop, viewportHeight]);

    const visibleItems = items.slice(visibleRange.start, visibleRange.end);
    const totalHeight = items.length * rowHeight;
    const offsetY = visibleRange.start * rowHeight;

    return (
        <div
            ref={containerRef}
            style={{
                ...listStyle,
                overflowY: suspendScroll ? "hidden" : "auto",
                position: "relative",
            }}
            onScroll={(event) => setScrollTop(event.currentTarget.scrollTop)}
        >
            {items.length === 0 ? (
                emptyContent
            ) : (
                <div style={{ height: totalHeight, position: "relative" }}>
                    <div
                        style={{
                            position: "absolute",
                            top: 0,
                            left: 0,
                            right: 0,
                            transform: `translateY(${offsetY}px)`,
                        }}
                    >
                        {visibleItems.map((item) => (
                            <label
                                key={item.key}
                                title={item.label}
                                style={{
                                    ...(item.checked ? activeRowStyle : rowStyle),
                                    height: rowHeight - 4,
                                    minHeight: rowHeight - 4,
                                    marginBottom: 4,
                                    overflow: "hidden",
                                }}
                            >
                                <input
                                    type="checkbox"
                                    style={inputStyle}
                                    checked={item.checked}
                                    onChange={item.onToggle}
                                />
                                <span
                                    style={{
                                        ...textStyle,
                                        minWidth: 0,
                                        overflow: "hidden",
                                        textOverflow: "ellipsis",
                                        whiteSpace: "nowrap",
                                    }}
                                >
                                    {item.label}
                                </span>
                                {item.badge && badgeStyle && <span style={badgeStyle}>{item.badge}</span>}
                            </label>
                        ))}
                    </div>
                </div>
            )}
        </div>
    );
};

export default VirtualCheckList;

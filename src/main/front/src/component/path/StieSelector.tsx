import React, {useEffect} from 'react';
import { Site } from '../../api/sites';

interface Props {
    sites: Site[];
    selectedId: number | null;
    onSelect: (id: number) => void;
}

const SiteSelector: React.FC<Props> = ({ sites, selectedId, onSelect }) => {

    return (


    <div className="mb-3">
        <label htmlFor="siteSelect" className="form-label fw-bold">사이트 선택</label>
        <select
            id="siteSelect"
            className="form-select"
            value={selectedId ?? ''}
            onChange={e => onSelect(Number(e.target.value))}
        >
            <option value="">-- 선택 --</option>
            {sites.map(site => (
                <option key={site.id} value={site.id}>{site.text}</option>
            ))}
        </select>
    </div>
    )
};

export default SiteSelector;
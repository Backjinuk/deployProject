import {useEffect, useState} from "react";
import {fetchSites, Site} from "../../api/sites";
import PathConverter from "./PathConverter";
import SiteSelector from "./StieSelector";
import 'bootstrap/dist/css/bootstrap.min.css';
import NickNameModal from "../login/NickNameModal";
import axios from "axios";

const ConverterMain = () => {
    const [sites, setSites] = useState<Site[]>([]);
    const [selected, setSelected] = useState<Site | null>(null);
    const [showModal, setShowModal] = useState(true);

    useEffect(() => {
        var deployUser = JSON.parse(localStorage.getItem('deployUser') || "");
        deployUser ? setShowModal(false) : setShowModal(true);

        axios.post("/api/sites", deployUser)
            .then((res) => {
                console.log(res.data)
                setSites(res.data) });
    }, []);

    const savedNick = (nick: string) => {

        axios.post('/api/login', { userName: nick }).then((res) => {
            setShowModal(false)
            localStorage.setItem('deployUser', JSON.stringify(res.data));
        });
    };

    return (
        <div className="container py-5">
            <NickNameModal show={showModal} onSave={savedNick}/>
            <h1 className="text-center mb-5">배포 경로 변환기</h1>
            <div className="card mb-4 shadow-sm">
                <div className="card-body">
                    <SiteSelector
                        sites={sites}
                        selectedId={selected?.id ?? null}
                        onSelect={id => setSelected(sites.find(s => s.id === id) || null)}
                    />
                </div>
            </div>
            {selected && (
                <div className="card shadow-sm">
                    <div className="card-body">
                        <PathConverter site={selected} />
                    </div>
                </div>
            )}
        </div>
    );
};


export default ConverterMain;
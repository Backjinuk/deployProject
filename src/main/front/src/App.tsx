import React from 'react';
import './App.css';
import ConverterMain from "./component/path/ConverterMain";
import DownloadPage from "./component/download/DownloadPage";
import { uiMode } from "./api/http";

function App() {


  return (
    <div className="App">
        {uiMode === "DOWNLOAD" ? <DownloadPage /> : <ConverterMain/>}
    </div>
  );
}

export default App;
